#!/bin/sh
# Maven wrapper for macOS/Linux: downloads Maven on first use (see .mvn/wrapper), then runs it.
set -e
BASEDIR=$(cd "$(dirname "$0")" && pwd)
URL=$(sed -n 's/^distributionUrl=//p' "$BASEDIR/.mvn/wrapper/maven-wrapper.properties" | tr -d '\r')
NAME=$(basename "$URL" .zip)
DEST="$HOME/.m2/wrapper/dists/$NAME"
if [ ! -x "$DEST/bin/mvn" ]; then
  echo "Downloading $URL" >&2
  TMP=$(mktemp -d)
  curl -fsSL "$URL" -o "$TMP/maven.zip"
  unzip -q "$TMP/maven.zip" -d "$TMP"
  mkdir -p "$(dirname "$DEST")"
  mv "$TMP"/apache-maven-* "$DEST"
  rm -rf "$TMP"
fi
exec "$DEST/bin/mvn" "$@"
