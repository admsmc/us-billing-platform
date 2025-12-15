# Dependency verification and locking

This repo uses two Gradle features to harden the software supply chain:

- **Dependency verification**: checksums for external artifacts, stored in `gradle/verification-metadata.xml`.
- **Dependency locking**: pinned resolved versions, stored in `gradle.lockfile` (root) and per-module `*/gradle.lockfile`.

Together, they make builds deterministic and make it harder for upstream dependency tampering to go unnoticed.

## How it works

### Dependency verification (checksums)
Gradle verifies downloaded artifacts against expected checksums in `gradle/verification-metadata.xml`.

Verification mode is configured as strict in `gradle.properties`:
- `org.gradle.dependency.verification=strict`

If a checksum mismatch occurs, the build fails.

### Dependency locking (pinned versions)
Gradle resolves dependency versions once and writes them to lockfiles. Subsequent builds reuse those locked versions.

Locking is enabled for all configurations and uses **strict** lock mode (see `build.gradle.kts`). If a lockfile is missing/out of date for a locked configuration, the build fails.

## Developer workflow

### When you change dependencies
Any time you add/remove/upgrade dependencies or plugins, regenerate both:
- `gradle/verification-metadata.xml`
- all impacted `gradle.lockfile` files

From the repo root:
- `./scripts/gradlew-java21.sh --no-daemon --write-verification-metadata sha256 --write-locks check`

Commit the updated lockfiles and verification metadata in the same PR as the dependency change.

### Updating only some locks (optional)
If you know exactly which modules/dependencies changed, you can use Gradle’s more targeted lock update mechanisms (e.g. `--update-locks`), but the repo-standard approach is the full refresh command above.

## CI enforcement
CI includes a guard that fails pull requests that appear to change dependencies/plugins **without** updating:
- `gradle/verification-metadata.xml`, and
- at least one `**/gradle.lockfile`.

This is intentionally conservative so dependency-affecting changes don’t silently land without updating these supply-chain control files.

## Troubleshooting

- **Checksum verification failure**: regenerate verification metadata using the standard command above.
- **Lock state missing / out of date**: regenerate lockfiles with `--write-locks` using the standard command above.
