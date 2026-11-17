#!/usr/bin/env bash
# Builds server/valtrin-server.jar from the mod's engine files plus server/src. No Minecraft needed.
# Needs a JDK and the Gson jar (set GSON=/path/to/gson-2.10.jar, default below).
set -euo pipefail
cd "$(dirname "$0")/.."
GSON="${GSON:-/opt/gradle-8.14.3/lib/gson-2.10.jar}"
OUT=server/build
# Files that need Minecraft (screens, commands, chat alerts, the mod entry point) are left out on purpose.
EXCLUDE=" AhOverlay FlipCommands SellerClick SettingsScreen ShiftKey StackInfo V ValtrinMod WelcomeScreen FlipAlerts ModAlertSink "
rm -rf "$OUT"; mkdir -p "$OUT/src/dev/valtrin" "$OUT/classes"
for f in src/main/java/dev/valtrin/*.java; do
  n=$(basename "$f" .java)
  case "$EXCLUDE" in *" $n "*) ;; *) cp "$f" "$OUT/src/dev/valtrin/";; esac
done
cp -r server/src/. "$OUT/src/"
javac -proc:none -nowarn -Xlint:none -cp "$GSON" -d "$OUT/classes" $(find "$OUT/src" -name '*.java')
( cd "$OUT/classes" && unzip -q -o "$GSON" -x 'META-INF/*' 'module-info.class' )
jar --create --file server/valtrin-server.jar --main-class dev.valtrin.ServerMain -C "$OUT/classes" .
echo "built server/valtrin-server.jar"
