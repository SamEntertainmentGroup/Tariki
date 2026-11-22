#!/usr/bin/env python3
"""Polygones administratifs exportés par osmium (pays et wilayas).

Sert à donner son numéro de wilaya à chaque lieu de la recherche d'itinéraire (ou le code de la Tunisie),
et à lire le contour des pays couverts pour le fond de carte (voir build_base_layer.py).
"""
import json
import re

WILAYA_LEVEL = "4"
COUNTRY_LEVEL = "2"
MAX_WILAYA = 58
# Pays couverts par la carte et les itinéraires (codes ISO à deux lettres).
COVERED_COUNTRIES = ("DZ", "TN")
# Code d'un lieu tunisien à la place du numéro de wilaya (l'appli affiche TN).
TUNISIA_CODE = 200
# Écart minimal entre deux sommets gardés (≈ 200 m) : assez précis pour ranger un lieu dans sa wilaya.
SIMPLIFY_DEGREES = 0.002


def iter_features(path):
    with open(path, "r", encoding="utf-8") as reader:
        for line in reader:
            line = line.strip("\x1e \t\r\n")
            if not line:
                continue
            try:
                feature = json.loads(line)
            except ValueError:
                continue
            if isinstance(feature, dict):
                yield feature


def polygons_of(geometry):
    """Liste de polygones ; chaque polygone est une liste d'anneaux [(lon, lat), ...], l'extérieur en premier."""
    kind = geometry.get("type")
    coordinates = geometry.get("coordinates")
    if not isinstance(coordinates, list):
        return []
    if kind == "Polygon":
        raw = [coordinates]
    elif kind == "MultiPolygon":
        raw = coordinates
    else:
        return []
    result = []
    for polygon in raw:
        rings = []
        for ring in polygon:
            points = [(float(p[0]), float(p[1])) for p in ring if isinstance(p, list) and len(p) >= 2]
            if len(points) >= 4:
                rings.append(points)
        if rings:
            result.append(rings)
    return result


def wilaya_code(properties):
    """Numéro de wilaya (1 à 58) lu dans ISO3166-2 (DZ-16) ou dans ref ; 0 si absent."""
    iso = str(properties.get("ISO3166-2") or "").strip()
    if iso and not iso.startswith("DZ-"):
        return 0  # un gouvernorat tunisien (TN-11) n'est pas une wilaya
    match = re.fullmatch(r"DZ-(\d{1,2})", iso)
    text = match.group(1) if match else str(properties.get("ref") or "").strip()
    if not text.isdigit():
        return 0
    code = int(text)
    return code if 1 <= code <= MAX_WILAYA else 0


def country_code(properties):
    """Code ISO à deux lettres d'un pays (admin_level 2), ou chaîne vide."""
    if str(properties.get("admin_level")) != COUNTRY_LEVEL:
        return ""
    return str(properties.get("ISO3166-1") or properties.get("ISO3166-1:alpha2") or "").strip().upper()


def is_covered(properties):
    return country_code(properties) in COVERED_COUNTRIES


def simplify(ring, step=SIMPLIFY_DEGREES):
    kept = [ring[0]]
    for point in ring[1:-1]:
        last = kept[-1]
        if abs(point[0] - last[0]) + abs(point[1] - last[1]) >= step:
            kept.append(point)
    kept.append(ring[-1])
    return kept if len(kept) >= 4 else ring


def signed_area(ring):
    return sum(ring[i][0] * ring[i + 1][1] - ring[i + 1][0] * ring[i][1] for i in range(len(ring) - 1)) / 2


def wound(ring, counter_clockwise):
    """Sens imposé (GeoJSON : extérieur dans le sens inverse des aiguilles d'une montre, trous dans l'autre)."""
    return ring if (signed_area(ring) > 0) == counter_clockwise else ring[::-1]


def inside_ring(lon, lat, ring):
    inside = False
    j = len(ring) - 1
    for i in range(len(ring)):
        xi, yi = ring[i]
        xj, yj = ring[j]
        if (yi > lat) != (yj > lat) and lon < (xj - xi) * (lat - yi) / (yj - yi) + xi:
            inside = not inside
        j = i
    return inside


class WilayaFinder:
    """Wilaya algérienne d'un point (1 à 58), sinon TUNISIA_CODE s'il est en Tunisie, sinon 0."""

    def __init__(self, features):
        self.areas = []
        tunisia = []
        for feature in features:
            properties = feature.get("properties") or {}
            if country_code(properties) == "TN":
                tunisia.append(feature)
                continue
            if str(properties.get("admin_level")) != WILAYA_LEVEL:
                continue
            code = wilaya_code(properties)
            if not code:
                continue
            self._add(code, feature)
        # Après les wilayas : un lieu algérien proche de la frontière garde son numéro.
        for feature in tunisia:
            self._add(TUNISIA_CODE, feature)

    def _add(self, code, feature):
        for rings in polygons_of(feature.get("geometry") or {}):
            rings = [simplify(ring) for ring in rings]
            outer = rings[0]
            box = (min(p[0] for p in outer), min(p[1] for p in outer),
                   max(p[0] for p in outer), max(p[1] for p in outer))
            self.areas.append((code, box, rings))

    @property
    def codes(self):
        return {code for code, _, _ in self.areas if code != TUNISIA_CODE}

    def find(self, lon, lat):
        for code, (west, south, east, north), rings in self.areas:
            if not (west <= lon <= east and south <= lat <= north):
                continue
            if inside_ring(lon, lat, rings[0]) and not any(inside_ring(lon, lat, hole) for hole in rings[1:]):
                return code
        return 0
