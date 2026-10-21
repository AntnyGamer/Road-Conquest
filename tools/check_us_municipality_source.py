"""Verify Census TIGERweb municipal feature contract used by RoadConquest.

A successful Kotlin compilation alone does not prove the national GeoJSON
service responds with the fields and geometries used by the app.
"""
import json
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

BASE = (
    "https://tigerweb.geo.census.gov/arcgis/rest/services/"
    "TIGERweb/Places_CouSub_ConCity_SubMCD/MapServer"
)


def request(layer, place, state, envelope, is_point=False):
    params = {
        "where": f"STATE = '{state}'",
        "geometry": ",".join(str(value) for value in envelope),
        "geometryType": "esriGeometryPoint" if is_point else "esriGeometryEnvelope",
        "inSR": "4326",
        "spatialRel": "esriSpatialRelIntersects",
        "outFields": "BASENAME,NAME,STATE,LSADC,FUNCSTAT,AREALAND,AREAWATER",
        "returnGeometry": "true",
        "outSR": "4326",
        "resultRecordCount": "100",
        "f": "geojson",
    }
    url = f"{BASE}/{layer}/query?" + urllib.parse.urlencode(params)
    for attempt in range(3):
        try:
            headers = {
                "User-Agent": "RoadConquest-Beta-CI/1.0 (+https://github.com/AntnyGamer/RoadConquest-Beta)",
                "Accept": "application/geo+json, application/json",
            }
            with urllib.request.urlopen(
                urllib.request.Request(url, headers=headers), timeout=22
            ) as response:
                return json.load(response)
        except (urllib.error.URLError, TimeoutError, json.JSONDecodeError) as error:
            if attempt == 2:
                raise RuntimeError("Census municipal GeoJSON service unreachable or returned invalid JSON") from error
            time.sleep(3)


def check(layer, place, state, envelope):
    data = request(layer, place, state, envelope)
    assert data.get("type") == "FeatureCollection", data
    features = data.get("features", [])
    assert features, f"No official {place} boundary for Census layer {layer}"
    for feature in features:
        props = feature.get("properties", {})
        assert props.get("STATE") == state, props
        if props.get("BASENAME", "").lower() != place.lower():
            continue
        shape = feature.get("geometry", {})
        assert shape.get("type") in ("Polygon", "MultiPolygon"), shape
        coords = shape["coordinates"]
        ring = coords[0] if shape["type"] == "Polygon" else coords[0][0]
        lon, lat = ring[0][:2]
        assert -180 < lon < 180 and -90 < lat < 90, (lat, lon)
        return
    sampled_names = [
        (feat.get("properties", {}).get("NAME"), feat.get("properties", {}).get("BASENAME"))
        for feat in features[:10]
    ]
    raise AssertionError(
        f"No matching Census municipality {place!r} in state {state}, "
        f"layer {layer}, bbox {envelope}; returned {sampled_names}"
    )


def check_point(layer, place, state, longitude, latitude):
    """Verify the exact GPS point lookup used by visited-town discovery."""
    data = request(layer, place, state, (longitude, latitude), is_point=True)
    assert data.get("type") == "FeatureCollection", data
    matches = [
        feature for feature in data.get("features", [])
        if feature.get("properties", {}).get("STATE") == state
        and feature.get("properties", {}).get("BASENAME", "").lower() == place.lower()
        and feature.get("geometry", {}).get("type") in ("Polygon", "MultiPolygon")
    ]
    assert matches, f"Point lookup missed {place} in Census layer {layer}"


if __name__ == "__main__":
    try:
        check(4, "Philadelphia", "42", (-75.19, 39.93, -75.12, 39.98))  # PA city
        check(1, "Upper Darby", "42", (-75.30, 39.94, -75.24, 39.98))  # PA township
        check(4, "Gloucester City", "34", (-75.14, 39.88, -75.11, 39.92))  # NJ city
        check(1, "Gloucester", "34", (-75.07, 39.81, -75.02, 39.85))  # NJ township
        check_point(4, "Philadelphia", "42", -75.163, 39.952)
        check_point(1, "Upper Darby", "42", -75.28, 39.956)
        check_point(4, "Gloucester City", "34", -75.12, 39.897)
        print("Census official polygon and GPS point contracts verified for PA and NJ.")
    except Exception as error:
        print("::error::" + str(error), file=sys.stderr)
        sys.exit(1)
