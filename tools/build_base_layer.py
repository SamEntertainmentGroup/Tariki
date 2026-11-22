#!/usr/bin/env python3
"""Fond de carte autour des pays couverts (Algérie et Tunisie) : mer, terres voisines, frontières et noms.

Le fichier produit (algeria-base.geojson.gz) est téléchargé par l'appli et dessiné sous les tuiles,
à tous les zooms. De bas en haut :
  - la mer est la couleur de fond de la carte ;
  - land : terres de la région (Natural Earth, simplifiées), en gris ;
  - dz_area : territoire des pays couverts, mer territoriale comprise (OpenStreetMap), en couleur mer :
    il efface les terres simplifiées qui débordent sur leurs côtes ;
  - dz_land : terres précises des pays couverts (terres OpenStreetMap découpées par ogr2ogr), en beige ;
  - border : frontières entre les autres pays (celles des pays couverts viennent des tuiles), plus la
    limite entre le Maroc et le Sahara occidental sur le parallèle 27°40′ ;
  - label : nom des pays, en arabe et en français.

Deux étapes, avec ogr2ogr entre les deux (voir le workflow de la carte) :
  area     : écrit le contour des pays couverts (sert à découper les terres OpenStreetMap) ;
  assemble : réunit tous les morceaux dans un seul fichier compressé.
"""
import argparse
import gzip
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from admin_areas import is_covered, iter_features, polygons_of, simplify, wound  # noqa: E402

# Région couverte par le fond (ouest, sud, est, nord) : les pays couverts et leurs voisins.
REGION = (-30.0, 0.0, 35.0, 52.0)
# Contour des pays couverts : ≈ 20 m, pour rester collé à la frontière dessinée par les tuiles.
AREA_SIMPLIFY_DEGREES = 0.0002
DECIMALS = 5
# Frontières déjà dessinées par les tuiles OpenStreetMap (codes et noms Natural Earth).
COVERED_SIDES = {"DZA", "TUN", "Algeria", "Tunisia"}
# Limite Maroc / Sahara occidental (27°40′), dans le fichier Natural Earth des zones disputées.
WESTERN_SAHARA_LIMIT = "Western Saharan claim"
# Entre le Maroc et le Sahara occidental, Natural Earth range le mur de sable (ligne de contrôle) avec le
# dernier bout de la limite 27°40′ près de l'Algérie : on ne garde que les morceaux au nord de cette latitude.
CONTROL_LINE_SIDES = {"MAR", "SAH"}
WESTERN_SAHARA_LATITUDE = 27.6


def rounded_rings(rings, counter_clockwise_outer=True):
    result = []
    for index, ring in enumerate(rings):
        points = [(round(x, DECIMALS), round(y, DECIMALS)) for x, y in ring]
        points = wound(points, counter_clockwise_outer if index == 0 else not counter_clockwise_outer)
        result.append([list(point) for point in points])
    return result


def covered_area(features):
    """Contour des pays couverts (mer territoriale comprise) en MultiPolygon, ou None s'il manque."""
    polygons = []
    for feature in features:
        if not is_covered(feature.get("properties") or {}):
            continue
        for rings in polygons_of(feature.get("geometry") or {}):
            polygons.append(rounded_rings([simplify(ring, AREA_SIMPLIFY_DEGREES) for ring in rings]))
    if not polygons:
        return None
    return {"type": "MultiPolygon", "coordinates": polygons}


def load_collection(path):
    data = json.loads(Path(path).read_text(encoding="utf-8"))
    return data.get("features") or []


def bbox_of(geometry):
    xs, ys = [], []

    def walk(value):
        if isinstance(value, list) and value and isinstance(value[0], (int, float)):
            xs.append(value[0])
            ys.append(value[1])
        elif isinstance(value, list):
            for item in value:
                walk(item)

    walk(geometry.get("coordinates"))
    if not xs:
        return None
    return min(xs), min(ys), max(xs), max(ys)


def in_region(geometry):
    box = bbox_of(geometry)
    if box is None:
        return False
    west, south, east, north = REGION
    return box[0] <= east and box[2] >= west and box[1] <= north and box[3] >= south


def prop(properties, *names):
    """Les fichiers Natural Earth écrivent les champs tantôt en majuscules, tantôt en minuscules."""
    for name in names:
        for key in (name, name.lower(), name.upper()):
            value = properties.get(key)
            if value not in (None, ""):
                return value
    return None


def border_sides(properties):
    return {prop(properties, "ADM0_A3_L"), prop(properties, "ADM0_A3_R"),
            prop(properties, "ADM0_LEFT"), prop(properties, "ADM0_RIGHT")}


def keep_border(properties):
    """Frontière à dessiner : pas celle d'un pays couvert, déjà dessinée par les tuiles."""
    return not border_sides(properties) & COVERED_SIDES


def without_control_line(feature):
    """Retire le mur de sable d'une ligne Maroc / Sahara occidental ; les autres frontières restent intactes."""
    properties = feature.get("properties") or {}
    geometry = feature.get("geometry") or {}
    if {prop(properties, "ADM0_A3_L"), prop(properties, "ADM0_A3_R")} != CONTROL_LINE_SIDES:
        return feature
    parts = geometry.get("coordinates") if geometry.get("type") == "MultiLineString" else [geometry.get("coordinates")]
    kept = [part for part in parts or [] if part and min(point[1] for point in part) >= WESTERN_SAHARA_LATITUDE]
    if not kept:
        return None
    return {**feature, "geometry": {"type": "MultiLineString", "coordinates": kept}}


def is_western_sahara_limit(properties):
    return prop(properties, "NAME") == WESTERN_SAHARA_LIMIT


def label_features(countries):
    labels = []
    west, south, east, north = REGION
    for feature in countries:
        properties = feature.get("properties") or {}
        x, y = prop(properties, "LABEL_X"), prop(properties, "LABEL_Y")
        if not isinstance(x, (int, float)) or not isinstance(y, (int, float)):
            continue
        if not (west <= x <= east and south <= y <= north):
            continue
        name = prop(properties, "NAME") or ""
        labels.append({
            "type": "Feature",
            "properties": {
                "kind": "label",
                "name": name,
                "name_ar": prop(properties, "NAME_AR") or name,
                "name_fr": prop(properties, "NAME_FR") or name,
                "rank": int(prop(properties, "LABELRANK") or 5),
            },
            "geometry": {"type": "Point", "coordinates": [round(x, 3), round(y, 3)]},
        })
    return labels


def geometry_features(features, kind, keep=lambda properties: True):
    result = []
    for feature in features:
        geometry = feature.get("geometry") or {}
        if not geometry.get("type") or not in_region(geometry):
            continue
        if not keep(feature.get("properties") or {}):
            continue
        result.append({"type": "Feature", "properties": {"kind": kind}, "geometry": geometry})
    return result


def assemble(dz_area, dz_land, ne_land, ne_borders, ne_disputed, ne_countries):
    features = geometry_features(ne_land, "land")
    features += [{"type": "Feature", "properties": {"kind": "dz_area"}, "geometry": dz_area}]
    features += geometry_features(dz_land, "dz_land")
    borders = [line for line in (without_control_line(feature) for feature in ne_borders) if line]
    features += geometry_features(borders, "border", keep=keep_border)
    features += geometry_features(ne_disputed, "border", keep=is_western_sahara_limit)
    features += label_features(ne_countries)
    return {"type": "FeatureCollection", "features": features}


def main():
    parser = argparse.ArgumentParser(description="Fond de carte autour des pays couverts.")
    steps = parser.add_subparsers(dest="step", required=True)
    first = steps.add_parser("area", help="contour des pays couverts pour ogr2ogr")
    first.add_argument("--admin", required=True, type=Path, help="polygones administratifs (osmium export)")
    first.add_argument("--output", required=True, type=Path)
    second = steps.add_parser("assemble", help="réunit les morceaux dans le fichier final")
    second.add_argument("--area", required=True, type=Path)
    second.add_argument("--dz-land", required=True, type=Path, help="terres des pays couverts (ogr2ogr)")
    second.add_argument("--ne-land", required=True, type=Path, help="terres Natural Earth découpées")
    second.add_argument("--ne-borders", required=True, type=Path)
    second.add_argument("--ne-disputed", required=True, type=Path, help="limites des zones disputées")
    second.add_argument("--ne-countries", required=True, type=Path)
    second.add_argument("--output", required=True, type=Path, help="fichier .geojson.gz")
    args = parser.parse_args()

    if args.step == "area":
        area = covered_area(iter_features(args.admin))
        if area is None:
            print("ÉCHEC : contour des pays couverts introuvable dans les données", file=sys.stderr)
            return 1
        collection = {"type": "FeatureCollection",
                      "features": [{"type": "Feature", "properties": {}, "geometry": area}]}
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(collection, separators=(",", ":")), encoding="utf-8")
        print(f"Contour des pays couverts : {len(area['coordinates'])} polygones, {args.output.stat().st_size / 1000:.0f} Ko")
        return 0

    area = load_collection(args.area)[0]["geometry"]
    collection = assemble(area, load_collection(args.dz_land), load_collection(args.ne_land),
                          load_collection(args.ne_borders), load_collection(args.ne_disputed),
                          load_collection(args.ne_countries))
    kinds = {}
    for feature in collection["features"]:
        kinds[feature["properties"]["kind"]] = kinds.get(feature["properties"]["kind"], 0) + 1
    if not kinds.get("dz_land"):
        print("ÉCHEC : aucune terre des pays couverts (vérifiez l'étape ogr2ogr)", file=sys.stderr)
        return 1
    data = json.dumps(collection, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with gzip.open(args.output, "wb", compresslevel=9) as writer:
        writer.write(data)
    detail = ", ".join(f"{kind} : {count}" for kind, count in sorted(kinds.items()))
    print(f"Fond de carte : {len(data) / 1e6:.1f} Mo, compressé {args.output.stat().st_size / 1e6:.1f} Mo ({detail})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
