#!/usr/bin/env bash
# Télécharge les données OpenStreetMap des pays couverts, puis les réunit en un seul fichier
# (work/region.osm.pbf) pour que les routes qui passent la frontière soient reliées.
# Source principale : Geofabrik. Si elle ne répond pas après plusieurs essais : openstreetmap.fr.
set -euo pipefail

COUNTRIES="algeria tunisia"
SOURCES="https://download.geofabrik.de/africa https://download.openstreetmap.fr/extracts/africa"

mkdir -p work out
for country in $COUNTRIES; do
  file="work/$country.osm.pbf"
  rm -f "$file"
  for source in $SOURCES; do
    url="$source/$country-latest.osm.pbf"
    echo "Téléchargement de $url"
    if curl --fail --location --retry 4 --retry-delay 30 --retry-all-errors --connect-timeout 30 \
      --output "$file" "$url"; then
      break
    fi
    echo "::warning::$url ne répond pas, essai avec la source suivante"
    rm -f "$file"
  done
  if [ ! -s "$file" ]; then
    echo "::error::Impossible de télécharger les données de $country. Relancez le workflow plus tard."
    exit 1
  fi
done

# Les pays ne sont pas mis à jour à la même heure : un objet commun (frontière, route qui la traverse)
# peut exister en deux versions. La fusion les garde toutes, comme un fichier d'historique (.osh.pbf),
# puis time-filter ne garde que la version actuelle de chaque objet.
osmium merge --overwrite --with-history --output work/region.osh.pbf \
  $(for country in $COUNTRIES; do echo "work/$country.osm.pbf"; done)
osmium time-filter --overwrite --output work/region.osm.pbf work/region.osh.pbf
rm -f work/region.osh.pbf
ls -l work/*.osm.pbf
