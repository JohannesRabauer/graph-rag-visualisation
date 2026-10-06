#!/usr/bin/env bash
# Fails unless every class in the built graphrag-core and graphrag-core-testkit
# jars has class-file major version 65 (Java 21), so Java 21 consumers can
# load the published artifacts.
set -euo pipefail

expected=65
for module in graphrag-core graphrag-core-testkit; do
  jar=$(ls "$module"/target/"$module"-*.jar | grep -Ev -- '-(sources|javadoc)\.jar$' | head -n 1)
  dir=$(mktemp -d)
  unzip -q "$jar" -d "$dir"
  majors=$(find "$dir" -name '*.class' -print0 | xargs -0 javap -v | sed -n 's/^ *major version: //p' | sort -u)
  rm -rf "$dir"
  echo "$jar: class-file major version(s): $majors"
  if [ "$majors" != "$expected" ]; then
    echo "::error::$jar must contain only major version $expected (Java 21) classes, found: $majors"
    exit 1
  fi
done
