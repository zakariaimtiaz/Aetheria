#!/bin/sh
# Maven Wrapper - delegates to local Maven if wrapper jar not configured
# Minimal wrapper for IntelliJ compatibility; uses system mvn
set -e
MAVEN_HOME="${MAVEN_HOME:-C:/apache-maven-3.9.15}"
if [ -f "$MAVEN_HOME/bin/mvn" ]; then
  exec "$MAVEN_HOME/bin/mvn" "$@"
else
  exec mvn "$@"
fi
