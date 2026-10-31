#!/usr/bin/env bash
# Renders Play's feature graphic (1024 × 500) from art/feature-graphic.svg with the first Wear OS screenshot.
# The store's images exist in English only (en-US, the default language): every translation of the
# listing shows them, so nothing is uploaded per language. The Wear OS screenshots themselves
# (fastlane/metadata/android/en-US/images/wearScreenshots, square, unmasked and unframed as Play asks)
# come straight from the emulator. Needs rsvg-convert and the Noto Sans font.
#
#   tools/render-store-assets.sh
set -euo pipefail

root=$(cd "$(dirname "$0")/.." && pwd)
images=$root/fastlane/metadata/android/en-US/images
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT

# librsvg only loads images next to the SVG, hence the copy.
cp "$images/wearScreenshots/1.png" "$tmp/screen.png"
sed -e "s|@SCREENSHOT@|screen.png|" -e "s|@TAGLINE@|Talk to AI from your wrist.|" \
    -e "s|@PROVIDERS@|Google Gemini · OpenAI · your own API key|" -e "s|@FONT@|Noto Sans|g" \
    "$root/art/feature-graphic.svg" > "$tmp/feature.svg"
rsvg-convert -w 1024 -h 500 "$tmp/feature.svg" -o "$images/featureGraphic.png"

echo "Rendered $images/featureGraphic.png"
