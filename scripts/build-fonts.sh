#!/usr/bin/env bash
# Regenerates the menu/HUD MSDF atlases (medium, semibold, bold) from TTF files.
# Usage: scripts/build-fonts.sh <msdf-atlas-gen binary> <medium> <semibold> <bold>
# Each face is a static TTF or a variable font with its weight, e.g. "Jura.ttf?wght=600".
# msdf-atlas-gen: https://github.com/Chlumsky/msdf-atlas-gen. The pixel range must match
# DISTANCE_RANGE in shaders/core/msdf.fsh.
set -euo pipefail

generator=$1
out="$(dirname "$0")/../src/main/resources/assets/modelmirage/ui/fonts"
charset="$(dirname "$0")/fonts-charset.txt"

build() {
	local source=(-font "$2")
	[[ $2 == *\?* ]] && source=(-varfont "$2")
	"$generator" "${source[@]}" -charset "$charset" -type mtsdf -size 64 -pxrange 10 -yorigin bottom \
		-format png -imageout "$out/$1.png" -json "$out/$1.json"
}

build medium "$2"
build semibold "$3"
build bold "$4"
