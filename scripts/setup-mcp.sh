#!/usr/bin/env bash
set -euo pipefail

jar='minecraft-mcp-26.2-fabric-v0.4.1.jar'
url="https://github.com/langyo/minecraft-mod-mcp/releases/download/v0.4.1/$jar"
expected='0c433b1bd2a77ec407be4177f6188a1c32d9f7baf669fc696b5c46a82d00c6b3'

mkdir -p run/mods
curl --fail --location --retry 2 --output "run/mods/$jar" "$url"
printf '%s  %s\n' "$expected" "run/mods/$jar" | sha256sum --check

printf 'Minecraft Mod MCP installed in run/mods/%s\n' "$jar"
