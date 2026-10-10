"""Live contract check for the NJOGIS municipal topology RoadConquest uses.

No external Python dependencies. A successful Android build does not prove that
the public data service accepts our ArcGIS GeoJSON query, so verify the exact
query, field names and geographic coordinate order before publishing a release.
"""
import json
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

BASE = (
    "https://maps.nj.gov/arcgis/rest/services/"
    "Framework/Government_Boundaries/MapServer/2/query"
)
QUERY = {
    "where": "MUN LIKE 'Gloucester%' OR NAME LIKE 'Gloucester%'",
    "outFields": "NAME,MUN,MUN_LABEL,MUN_TYPE,COUNTY,SQ_MILES,POP2020",
    "returnGeometry": "true",
    "outSR": "4326",
    "resultRecordCount": "80",
    "f": "geojson",
}


def main():
    url = BASE + "?" + urllib.parse.urlencode(QUERY)
    data = None
    for attempt in range(3):
        try:
            request = urllib.request.Request(
                url,
                headers={"User-Agent": "RoadConquest-Beta-CI/1.0 (+https://github.com/AntnyGamer/RoadConquest-Beta)",
                         "Accept": "application/geo+json, application/json"},
            )
            with urllib.request.urlopen(request, timeout=18) as response:
                data = json.load(response)
            break
        except (urllib.error.URLError, TimeoutError) as error:
            if attempt == 2:
                raise RuntimeError("NJOGIS municipal GeoJSON endpoint unreachable") from error
            time.sleep(2)
    assert data is not None and data.get("type") == "FeatureCollection", data
    features = data.get("features", [])
    assert features, "NJOGIS returned no municipality GeoJSON features"
    names = set()
    for feature in features:
        props = feature.get("properties", {})
        name = str(props.get("NAME", ""))
        if "gloucester" in name.lower():
            names.add(name)
        shape = feature.get("geometry", {})
        assert shape.get("type") in ("Polygon", "MultiPolygon"), name
        polygons = shape.get("coordinates", [])
        assert polygons, name
        polygon = polygons if shape["type"] == "Polygon" else polygons[0]
        point = polygon[0][0]
        assert -76 < point[0] < -73 and 38 < point[1] < 42, (
            "Expected WGS84 GeoJSON [longitude, latitude]; got " + str(point)
        )
    assert any("gloucester" in x.lower() and "township" in x.lower() for x in names), names
    assert any("gloucester" in x.lower() and "city" in x.lower() for x in names), names
    print("NJOGIS official source GeoJSON verified. Gloucester City and Township are distinct features.")


if __name__ == "__main__":
    try:
        main()
    except Exception as exc:
        print("::error::" + str(exc), file=sys.stderr)
        sys.exit(1)
