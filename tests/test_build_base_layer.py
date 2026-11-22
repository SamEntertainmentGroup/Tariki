import gzip
import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "tools"))

import admin_areas as aa
import build_base_layer as bb


def square(west, south, east, north):
    return [[west, south], [east, south], [east, north], [west, north], [west, south]]


def feature(geometry_type, coordinates, **properties):
    return {"type": "Feature", "properties": properties, "geometry": {"type": geometry_type, "coordinates": coordinates}}


MOROCCO = feature("Polygon", [square(-13, 28, -1, 35)], boundary="administrative", admin_level="2", **{"ISO3166-1": "MA"})
ALGERIA = feature("Polygon", [square(-8, 19, 12, 37)], boundary="administrative", admin_level="2", **{"ISO3166-1": "DZ"})
TUNISIA = feature("Polygon", [square(8, 30, 11, 37)], boundary="administrative", admin_level="2", **{"ISO3166-1": "TN"})


def write_lines(path, features):
    path.write_text("".join("\x1e" + json.dumps(f) + "\n" for f in features), encoding="utf-8")


def write_collection(path, features):
    path.write_text(json.dumps({"type": "FeatureCollection", "features": features}), encoding="utf-8")


class CoveredAreaTest(unittest.TestCase):
    def test_algeria_and_tunisia_are_kept_with_outer_rings_counter_clockwise(self):
        area = bb.covered_area([MOROCCO, TUNISIA, ALGERIA])
        self.assertEqual(area["type"], "MultiPolygon")
        self.assertEqual(len(area["coordinates"]), 2)
        for polygon in area["coordinates"]:
            self.assertGreater(aa.signed_area(polygon[0]), 0)
        self.assertIsNone(bb.covered_area([MOROCCO]))


class AssembleTest(unittest.TestCase):
    def test_layers_order_borders_and_labels(self):
        area = bb.covered_area([ALGERIA])
        dz_land = [feature("Polygon", [square(-8, 19, 12, 36.5)])]
        ne_land = [feature("Polygon", [square(-17, 11, 19, 41)]), feature("Polygon", [square(100, 0, 101, 1)])]
        borders = [
            feature("LineString", [[-5, 20], [5, 20]], adm0_a3_l="MLI", adm0_a3_r="NER"),
            feature("LineString", [[8, 30], [8, 35]], adm0_a3_l="DZA", adm0_a3_r="TUN"),
            feature("LineString", [[10, 32], [12, 32]], adm0_a3_l="TUN", adm0_a3_r="LBY"),
        ]
        disputed = [
            feature("MultiLineString", [[[-13.2, 27.66], [-8.8, 27.66]]], NAME="Western Saharan claim"),
            feature("MultiLineString", [[[30, 30], [31, 31]]], NAME="Other claim"),
        ]
        countries = [
            feature("Polygon", [], NAME="Tunisia", NAME_AR="تونس", NAME_FR="Tunisie", LABEL_X=9.5, LABEL_Y=34.0),
            feature("Polygon", [], NAME="Norway", NAME_AR="النرويج", NAME_FR="Norvège", LABEL_X=9.0, LABEL_Y=61.0),
        ]
        collection = bb.assemble(area, dz_land, ne_land, borders, disputed, countries)
        kinds = [f["properties"]["kind"] for f in collection["features"]]
        self.assertEqual(kinds, ["land", "dz_area", "dz_land", "border", "border", "label"])
        self.assertEqual(collection["features"][4]["geometry"]["coordinates"][0][0], [-13.2, 27.66])
        label = collection["features"][-1]["properties"]
        self.assertEqual((label["name_ar"], label["name_fr"]), ("تونس", "Tunisie"))

    def test_western_sahara_control_line_is_dropped_but_the_27_40_end_is_kept(self):
        line = feature("MultiLineString", [[[-8.82, 27.66], [-9.08, 27.09]], [[-10.76, 27.02], [-15.15, 21.44]],
                                           [[-8.75, 27.66], [-8.68, 27.66]]], ADM0_A3_L="SAH", ADM0_A3_R="MAR")
        kept = bb.without_control_line(line)
        self.assertEqual(kept["geometry"]["coordinates"], [[[-8.75, 27.66], [-8.68, 27.66]]])
        only_wall = feature("MultiLineString", [[[-10.76, 27.02], [-15.15, 21.44]]], ADM0_A3_L="SAH", ADM0_A3_R="MAR")
        self.assertIsNone(bb.without_control_line(only_wall))
        other = feature("LineString", [[-17, 21], [-13, 21]], ADM0_A3_L="SAH", ADM0_A3_R="MRT")
        self.assertIs(bb.without_control_line(other), other)

    def test_command_line_writes_gzip(self):
        with tempfile.TemporaryDirectory() as folder:
            folder = Path(folder)
            write_lines(folder / "admin", [ALGERIA])
            argv = sys.argv
            try:
                sys.argv = ["x", "area", "--admin", str(folder / "admin"), "--output", str(folder / "dz.geojson")]
                self.assertEqual(bb.main(), 0)
                write_collection(folder / "dz-land", [feature("Polygon", [square(-8, 19, 12, 36.5)])])
                write_collection(folder / "ne-land", [feature("Polygon", [square(-17, 11, 19, 41)])])
                write_collection(folder / "borders", [])
                write_collection(folder / "disputed", [])
                write_collection(folder / "countries", [])
                sys.argv = ["x", "assemble", "--area", str(folder / "dz.geojson"), "--dz-land", str(folder / "dz-land"),
                            "--ne-land", str(folder / "ne-land"), "--ne-borders", str(folder / "borders"),
                            "--ne-disputed", str(folder / "disputed"),
                            "--ne-countries", str(folder / "countries"), "--output", str(folder / "out" / "base.geojson.gz")]
                self.assertEqual(bb.main(), 0)
            finally:
                sys.argv = argv
            data = json.loads(gzip.decompress((folder / "out" / "base.geojson.gz").read_bytes()))
            self.assertEqual(data["type"], "FeatureCollection")

    def test_missing_covered_land_fails(self):
        with tempfile.TemporaryDirectory() as folder:
            folder = Path(folder)
            write_collection(folder / "dz", [feature("MultiPolygon", [[square(-8, 19, 12, 37)]])])
            for name in ("dz-land", "ne-land", "borders", "disputed", "countries"):
                write_collection(folder / name, [])
            argv = sys.argv
            try:
                sys.argv = ["x", "assemble", "--area", str(folder / "dz"), "--dz-land", str(folder / "dz-land"),
                            "--ne-land", str(folder / "ne-land"), "--ne-borders", str(folder / "borders"),
                            "--ne-disputed", str(folder / "disputed"),
                            "--ne-countries", str(folder / "countries"), "--output", str(folder / "base.geojson.gz")]
                self.assertEqual(bb.main(), 1)
            finally:
                sys.argv = argv


if __name__ == "__main__":
    unittest.main()
