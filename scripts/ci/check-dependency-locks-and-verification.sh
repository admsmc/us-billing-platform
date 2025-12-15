#!/usr/bin/env bash
set -euo pipefail

BASE_SHA=${1:-}
HEAD_SHA=${2:-}

if [[ -z "${BASE_SHA}" || -z "${HEAD_SHA}" ]]; then
  echo "usage: $0 <base_sha> <head_sha>" >&2
  exit 2
fi

changed_files=$(git diff --name-only "${BASE_SHA}" "${HEAD_SHA}")

# If nothing changed, nothing to enforce.
if [[ -z "${changed_files}" ]]; then
  exit 0
fi

is_dependency_affecting_change=0

# Files that commonly contain dependency declarations or plugin versions.
# (Keep conservative; we only enforce if the diff looks dependency-related.)
relevant_build_files=$(echo "${changed_files}" | grep -E '(^|/)(build\.gradle\.kts|settings\.gradle\.kts|gradle\.properties|libs\.versions\.toml)$' || true)

if [[ -n "${relevant_build_files}" ]]; then
  while IFS= read -r f; do
    [[ -z "${f}" ]] && continue

    # Detect dependency-affecting edits (adds/removes) in build files.
    # We intentionally look for common dependency configuration calls and plugin version declarations.
    if git diff "${BASE_SHA}" "${HEAD_SHA}" -- "${f}" | grep -Eq \
      '^[+-].*(implementation|api|compileOnly|runtimeOnly|testImplementation|testRuntimeOnly|kapt|annotationProcessor|constraints|platform|enforcedPlatform)\(' \
      || git diff "${BASE_SHA}" "${HEAD_SHA}" -- "${f}" | grep -Eq '^[+-].*id\("[^"]+"\) version ' \
      || git diff "${BASE_SHA}" "${HEAD_SHA}" -- "${f}" | grep -Eq '^[+-].*kotlin\("[^"]+"\) version ' \
      || git diff "${BASE_SHA}" "${HEAD_SHA}" -- "${f}" | grep -Eq '^[+-].*dependency\("[^"]+"\) version ' \
      ; then
      is_dependency_affecting_change=1
      break
    fi
  done <<< "${relevant_build_files}"
fi

if [[ "${is_dependency_affecting_change}" -eq 0 ]]; then
  exit 0
fi

has_verification_update=0
has_lock_update=0

if echo "${changed_files}" | grep -q '^gradle/verification-metadata\.xml$'; then
  has_verification_update=1
fi

if echo "${changed_files}" | grep -Eq '(^|/)gradle\.lockfile$'; then
  has_lock_update=1
fi

if [[ "${has_verification_update}" -eq 1 && "${has_lock_update}" -eq 1 ]]; then
  exit 0
fi

cat >&2 <<'EOF'
Dependency-affecting build changes detected, but Gradle supply-chain control files were not updated.

If you changed dependencies or plugin versions, you must regenerate and commit BOTH:
- gradle/verification-metadata.xml
- gradle.lockfile (root) and any impacted */gradle.lockfile

Run from the repo root:
  ./scripts/gradlew-java21.sh --no-daemon --write-verification-metadata sha256 --write-locks check
EOF

exit 1
