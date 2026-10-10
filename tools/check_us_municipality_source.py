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


def request(layer, place, state):
    params = {
        "where": f"STATE = '{state}' AND (BASENAME = '{place}' OR NAME = '{place}')",
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


def check(layer, place, state):
    data = request(layer, place, state)
    assert data.get("type") == "FeatureCollection", data
    features = data.get("features", [])
    assert features, f"No official {place} boundary for Census layer {layer}"
    for feature in features:
        props = feature.get("properties", {})
        assert props.get("STATE") == state, props
        assert props.get("BASENAME", "").lower() == place.lower(), props
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
        check(4, "Philadelphia", "42")  # Pennsylvania incorporated city
        check(1, "Washington", "42")    # Pennsylvania minor civil divisions
        print("Census official city + township GeoJSON boundary contract verified.")
    except Exception as error:
        print("::error::" + str(error), file=sys.stderr)
        sys.exit(1)
