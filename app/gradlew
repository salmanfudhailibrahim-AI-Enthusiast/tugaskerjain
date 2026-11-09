#!/bin/sh
# Pembungkus sederhana: mengunduh Gradle 8.7 sekali, lalu menjalankannya.
set -e
V=8.7
BASE="${GRADLE_USER_HOME:-$HOME/.gradle}/bootstrap"
if [ ! -x "$BASE/gradle-$V/bin/gradle" ]; then
  mkdir -p "$BASE"
  curl -fsSL "https://services.gradle.org/distributions/gradle-$V-bin.zip" -o "$BASE/g.zip"
  unzip -q -o "$BASE/g.zip" -d "$BASE"
fi
exec "$BASE/gradle-$V/bin/gradle" "$@"
