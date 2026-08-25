# Rename Project: level1 → level2

Date: 2026-08-25
Status: Ready for implementation

## Scope

Full rename: filesystem, Android package/applicationId, signing properties, docs, git remote.
Fresh install on device — no data migration needed.

## Phase 1 — Forgejo remote

- Rename repo via Forgejo API: `PATCH /api/v1/repos/boss/level2 {"name":"level2"}`
- Update local origin URL after filesystem move

## Phase 2 — Filesystem

- `mv /home/tpa/code/level2 /home/tpa/code/level2`
- `mv .../level2/level1 .../level2/level2` (inner project dir)
- Fix origin URL (`boss/level2.git`)

## Phase 3 — Android package rename

- Move source trees: `app/src/{main,test,androidTest}/kotlin/io/securitycam/level1` → `.../level2`
- Mechanical replace `io.securitycam.level2` → `io.securitycam.level2` across ~110 .kt files
- `build.gradle.kts`: namespace, applicationId (+ staging suffix)
- Rename `Level1App.kt` → `Level2App.kt` (class + manifest reference)
- Manifest label `"level1"` → `"level2"`
- `staging-rules.pro`: keep rule `io.securitycam.level2` → `io.securitycam.level2`
- Runner script defaults (FQCN + PKG)

## Phase 4 — String literals

- SecretStore: `"level1_secrets"` → `"level2_secrets"`
- Wakelock tag, MediaStore subdirectory, temp-file prefix

## Phase 5 — Signing config

- gradle.properties: `LEVEL1_*` → `LEVEL2_*` (property names only)
- Keep existing keystore file & key alias values
- Update build.gradle.kts property lookups

## Phase 6 — Docs

- AGENTS.md: full pass
- docs/plans/*.md: mechanical replacement (~72 occurrences)

## Phase 7 — Verify & ship

1. Full unit suite + assembleDebug from new path
2. Uninstall old io.securitycam.level2 from A13, install new APK
3. Smoke test: grant permissions, monitoring, verify "Hallway — Monitoring"
4. Single commit, push to renamed remote
