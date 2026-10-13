#!/bin/sh
# Builds the mod without Gradle: Minecraft 26.x is no longer obfuscated, so javac against the jars of the Prism instance is enough.
# Usage: ./build.sh [path to Prism instance]   (default: ~/.local/share/PrismLauncher/instances/26.3)
set -e
cd "$(dirname "$0")"
PRISM="${PRISM:-$HOME/.local/share/PrismLauncher}"
INST="${1:-$PRISM/instances/26.3}"
MODS="$INST/minecraft/mods"
JAVAC="$(ls "$PRISM"/java/*/bin/javac 2>/dev/null | sort | tail -1)"; [ -x "$JAVAC" ] || JAVAC=javac
JAR="$(dirname "$JAVAC")/jar"; [ -x "$JAR" ] || JAR=jar
MC=$(grep -o '"version": *"[^"]*"' "$INST/mmc-pack.json" | sed -n '2p' | cut -d'"' -f4)
[ -f "$PRISM/libraries/com/mojang/minecraft/$MC/minecraft-$MC-client.jar" ] || MC=26.3
TMP=build/tmp; rm -rf build; mkdir -p "$TMP/fapi" "$TMP/classes"
(cd "$TMP/fapi" && unzip -qo "$(ls "$MODS"/fabric-api-*.jar | tail -1)" 'META-INF/jars/*')
CP="$PRISM/libraries/com/mojang/minecraft/$MC/minecraft-$MC-client.jar"
for j in "$TMP"/fapi/META-INF/jars/*.jar "$MODS"/litematica-fabric-*.jar "$MODS"/malilib-fabric-*.jar \
  $(ls "$PRISM"/libraries/net/fabricmc/fabric-loader/*/fabric-loader-*.jar | sort -V | tail -1) \
  $(find "$PRISM/libraries" -name 'gson-*.jar' -o -name 'brigadier-*.jar' -o -name 'guava-*.jar' -o -name 'datafixerupper-*.jar' -o -name 'authlib-*.jar' -o -name 'fastutil-*.jar' -o -name 'joml-*.jar' -o -name 'slf4j-api-*.jar' -o -name 'jspecify-*.jar' -o -name 'netty-buffer-*.jar' -o -name 'netty-common-*.jar' | sort -V -r); do
  CP="$CP:$j"
done
"$JAVAC" -nowarn -proc:none -cp "$CP" -d "$TMP/classes" $(find src -name '*.java')
mkdir -p "$TMP/classes/web" && cp ../public/index.html ../public/logic.js ../public/mcmodel.js ../public/render.js "$TMP/classes/web/"
cp resources/fabric.mod.json "$TMP/classes/"
"$JAR" --create --file "build/materiallist-litematica-2.0.0+$MC.jar" -C "$TMP/classes" .
rm -rf "$TMP"
echo "Done: $(pwd)/build/materiallist-litematica-2.0.0+$MC.jar"

