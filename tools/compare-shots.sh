#!/usr/bin/env bash
# Side by side: the client's screenshots (left) and the prototype's (right), stage 6 of _plan.
#   tools/compare-shots.sh <prototype screenshots/cloud dir>
# Client shots — composeApp/build/screenshots-{adaptive,native}/<420|800|1280>-<theme>/ (AdaptiveShotsTest,
# NativeAppsShotsTest). Prototype — screenshots/cloud/{compact,medium,expanded}/<screen>-<theme>.png of
# prototype-rikka (a88827e). Out — build/screenshots/new-ui/compare/<class>/<screen>-<theme>.png.
# Needs ImageMagick's montage.
set -euo pipefail
cd "$(dirname "$0")/.."
proto="${1:?prototype screenshots/cloud dir}"
out=build/screenshots/new-ui/compare
rm -rf "$out"

# client shot → prototype screen
pairs=(
  adaptive/01-home:r-home
  adaptive/02-orders:r-orders
  adaptive/03-order:r-order
  adaptive/04-pos:r-pos
  adaptive/05c-pos-receipt-lines:r-pos-receipt
  adaptive/05a-pos-catalog:r-pos-catalog
  adaptive/06-marketing:r-marketing
  adaptive/06a-marketing-promotions:r-promotions
  adaptive/11-dishes:r-dishes
  adaptive/12-dish:r-dish
  adaptive/13-clients:r-clients
  adaptive/15-codes:r-codes
  adaptive/07-connect-choice:connect
  adaptive/09-connect-sign-in:signin
  native/02-assistant-reply:r-assistant
  native/10-promotion-editor:r-promotion-edit
  native/20-setting-editor:r-settings
)
declare -A class=([420]=compact [800]=medium [1280]=expanded)

n=0
for pair in "${pairs[@]}"; do
  shot="${pair%%:*}"; screen="${pair##*:}"
  for w in 420 800 1280; do
    for theme in light dark; do
      mine="composeApp/build/screenshots-${shot%%/*}/$w-$theme/${shot#*/}.png"
      theirs="$proto/${class[$w]}/$screen-$theme.png"
      [ -f "$mine" ] && [ -f "$theirs" ] || { echo "skip: $mine | $theirs" >&2; continue; }
      mkdir -p "$out/${class[$w]}"
      montage -label "клиент ${shot#*/}" "$mine" -label "прототип $screen" "$theirs" \
        -tile 2x1 -geometry "x900+24+16" -pointsize 22 -background '#d4d4d4' \
        "$out/${class[$w]}/$screen-$theme.png"
      n=$((n + 1))
    done
  done
done
echo "$n sheets → $out"
