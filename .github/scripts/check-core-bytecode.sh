#!/usr/bin/env bash
# Fails unless every class in the built graphrag-core jar has class-file
# major version 65 (Java 21), so Java 21 consumers can load the artifact.
set -euo pipefail

expected=65
jar=$(ls graphrag-core/target/graphrag-core-*.jar | grep -Ev -- '-(sources|javadoc)\.jar$' | head -n 1)
dir=$(mktemp -d)
trap 'rm -rf "$dir"' EXIT
unzip -q "$jar" -d "$dir"

majors=$(find "$dir" -name '*.class' -print0 | xargs -0 javap -v | sed -n 's/^ *major version: //p' | sort -u)
echo "$jar: class-file major version(s): $majors"
if [ "$majors" != "$expected" ]; then
  echo "::error::$jar must contain only major version $expected (Java 21) classes, found: $majors"
  exit 1
fi
