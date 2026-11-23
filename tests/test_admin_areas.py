import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "tools"))

import admin_areas as aa


def square(west, south, east, north):
    return [[west, south], [east, south], [east, north], [west, north], [west, south]]


def area(level, coordinates, kind="Polygon", **tags):
    return {"type": "Feature", "properties": {"boundary": "administrative", "admin_level": level, **tags},
            "geometry": {"type": kind, "coordinates": coordinates}}


class WilayaTest(unittest.TestCase):
    def test_code_from_iso_or_ref(self):
        self.assertEqual(aa.wilaya_code({"ISO3166-2": "DZ-16"}), 16)
        self.assertEqual(aa.wilaya_code({"ISO3166-2": "DZ-05"}), 5)
        self.assertEqual(aa.wilaya_code({"ref": "58"}), 58)
        self.assertEqual(aa.wilaya_code({"ref": "99"}), 0)
        self.assertEqual(aa.wilaya_code({"ISO3166-2": "TN-11"}), 0)

    def test_point_in_wilaya_respects_holes_and_multipolygons(self):
        features = [
            area("4", [square(0, 0, 10, 10), square(4, 4, 6, 6)], **{"ISO3166-2": "DZ-01"}),
            area("4", [[square(20, 0, 21, 1)], [square(30, 0, 31, 1)]], "MultiPolygon", ref="2"),
            area("8", [square(0, 0, 10, 10)], ref="3"),
        ]
        finder = aa.WilayaFinder(features)
        self.assertEqual(finder.find(1, 1), 1)
        self.assertEqual(finder.find(5, 5), 0)
        self.assertEqual(finder.find(30.5, 0.5), 2)
        self.assertEqual(finder.find(50, 50), 0)
        self.assertEqual(finder.codes, {1, 2})

    def test_tunisian_places_get_the_tunisia_code_and_governorates_no_number(self):
        features = [
            area("2", [square(8, 30, 11, 37)], **{"ISO3166-1": "TN"}),
            area("4", [square(9, 35, 10, 36)], **{"ISO3166-2": "TN-11"}, ref="11"),
            area("4", [square(0, 30, 8, 37)], **{"ISO3166-2": "DZ-16"}),
        ]
        finder = aa.WilayaFinder(features)
        self.assertEqual(finder.find(9.5, 35.5), aa.TUNISIA_CODE)
        self.assertEqual(finder.find(4, 33), 16)
        self.assertEqual(finder.codes, {16})

    def test_country_code(self):
        self.assertEqual(aa.country_code({"admin_level": "2", "ISO3166-1": "DZ"}), "DZ")
        self.assertEqual(aa.country_code({"admin_level": "2", "ISO3166-1:alpha2": "tn"}), "TN")
        self.assertEqual(aa.country_code({"admin_level": "4", "ISO3166-1": "DZ"}), "")


if __name__ == "__main__":
    unittest.main()
