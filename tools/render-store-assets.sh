#!/usr/bin/env bash
# Renders Play's feature graphic (1024 × 500) for every store language from art/feature-graphic.svg,
# with the first Wear OS screenshot of that language (en-US for the languages without screenshots of
# their own). The Wear OS screenshots themselves (fastlane/metadata/android/<locale>/images/wearScreenshots,
# square, unmasked and unframed as Play asks) come straight from the emulator.
# Needs rsvg-convert and the Noto Sans and Noto Sans CJK fonts.
#
#   tools/render-store-assets.sh
set -euo pipefail

root=$(cd "$(dirname "$0")/.." && pwd)
meta=$root/fastlane/metadata/android
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT

# locale | tagline (the app's welcome_tagline) | providers line | font
while IFS='|' read -r locale tagline providers font; do
    [ -z "$locale" ] && continue
    screenshot=$meta/$locale/images/wearScreenshots/1.png
    [ -f "$screenshot" ] || screenshot=$meta/en-US/images/wearScreenshots/1.png
    # librsvg only loads images next to the SVG, hence the copy.
    cp "$screenshot" "$tmp/screen.png"
    sed -e "s|@SCREENSHOT@|screen.png|" -e "s|@TAGLINE@|$tagline|" -e "s|@PROVIDERS@|$providers|" \
        -e "s|@FONT@|$font|g" "$root/art/feature-graphic.svg" > "$tmp/feature.svg"
    mkdir -p "$meta/$locale/images"
    rsvg-convert -w 1024 -h 500 "$tmp/feature.svg" -o "$meta/$locale/images/featureGraphic.png"
    cp "$meta/en-US/images/icon.png" "$meta/$locale/images/icon.png" 2>/dev/null || true
done <<'EOF'
en-US|Talk to AI from your wrist.|Google Gemini · OpenAI · your own API key|Noto Sans
de-DE|Sprich mit KI am Handgelenk.|Google Gemini · OpenAI · dein eigener API-Schlüssel|Noto Sans
es-ES|Habla con la IA desde tu muñeca.|Google Gemini · OpenAI · tu propia clave de API|Noto Sans
fr-FR|Parlez à l’IA depuis votre poignet.|Google Gemini · OpenAI · votre propre clé API|Noto Sans
it-IT|Parla con l’IA dal polso.|Google Gemini · OpenAI · la tua chiave API|Noto Sans
ja-JP|手首から AI と話そう。|Google Gemini · OpenAI · ご自身の API キー|Noto Sans CJK JP
ko-KR|손목에서 AI와 대화하세요.|Google Gemini · OpenAI · 본인의 API 키|Noto Sans CJK KR
nl-NL|Praat met AI vanaf je pols.|Google Gemini · OpenAI · je eigen API-sleutel|Noto Sans
pl-PL|Rozmowa z AI prosto z nadgarstka.|Google Gemini · OpenAI · Twój klucz API|Noto Sans
pt-BR|Fale com a IA pelo pulso.|Google Gemini · OpenAI · sua própria chave de API|Noto Sans
sv-SE|Prata med AI från handleden.|Google Gemini · OpenAI · din egen API-nyckel|Noto Sans
tr-TR|Bileğinizden yapay zekayla konuşun.|Google Gemini · OpenAI · kendi API anahtarınız|Noto Sans
zh-CN|抬腕即可与 AI 对话。|Google Gemini · OpenAI · 您自己的 API 密钥|Noto Sans CJK SC
EOF

echo "Rendered feature graphics into $meta/*/images"
