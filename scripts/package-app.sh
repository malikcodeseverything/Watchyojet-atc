#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd "$(dirname "$0")/.." && pwd)"
cd "$project_dir"

if ! command -v jpackage >/dev/null 2>&1; then
  echo "jpackage was not found. Install a full JDK 21 distribution." >&2
  exit 1
fi

mvn --batch-mode --no-transfer-progress clean test javafx:jlink
rm -rf target/dist
mkdir -p target/dist

jpackage \
  --type app-image \
  --name WatchyoJet \
  --description "Educational air-traffic conflict detection simulator" \
  --vendor "WatchyoJet contributors" \
  --java-options "--enable-native-access=javafx.graphics,javafx.web" \
  --runtime-image target/watchyojet \
  --module com.watchyojet/com.watchyojet.WYJApp \
  --dest target/dist

echo "Application image created in target/dist"
