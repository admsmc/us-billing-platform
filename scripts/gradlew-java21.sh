#!/usr/bin/env bash
set -euo pipefail

# Runs ./gradlew using a Java 21 runtime.
# This helps when your system default java is newer than what Kotlin/Gradle plugins support.

if [[ -n "${JAVA_HOME:-}" ]]; then
  exec ./gradlew "$@"
fi

# macOS: prefer /usr/libexec/java_home if present.
if command -v /usr/libexec/java_home >/dev/null 2>&1; then
  JAVA_HOME_21=$(/usr/libexec/java_home -v 21 2>/dev/null || true)
  if [[ -n "${JAVA_HOME_21}" ]]; then
    export JAVA_HOME="$JAVA_HOME_21"
    exec ./gradlew "$@"
  fi
fi

cat >&2 <<'EOF'
Could not locate a Java 21 installation.

Set JAVA_HOME to a Java 21 JDK and re-run, e.g.:
  JAVA_HOME=/path/to/jdk-21 ./gradlew <task>
EOF
exit 1
