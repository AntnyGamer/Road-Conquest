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


def request(layer, place, state, envelope):
    params = {
        "where": f"STATE = '{state}'",
        "geometry": ",".join(str(value) for value in envelope),
        "geometryType": "esriGeometryEnvelope",
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
        except (urllib.error.URLError, TimeoutError) as error:
            if attempt == 2:
                raise RuntimeError("Census municipal GeoJSON service unreachable") from error
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
    raise AssertionError("No matching Census municipality found")


if __name__ == "__main__":
    try:
        check(4, "Philadelphia", "42", (-75.19, 39.93, -75.12, 39.98))  # PA city
        check(1, "Upper Darby", "42", (-75.30, 39.94, -75.24, 39.98))  # PA township
        check(4, "Gloucester", "34", (-75.14, 39.88, -75.11, 39.92))  # NJ city
        check(1, "Gloucester", "34", (-75.07, 39.81, -75.02, 39.85))  # NJ township
        print("Census official city + township GeoJSON contract verified for PA and NJ.")
    except Exception as error:
        print("::error::" + str(error), file=sys.stderr)
        sys.exit(1)
