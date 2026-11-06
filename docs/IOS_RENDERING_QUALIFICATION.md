# iOS rendering candidate qualification

Candidate: update Compose Multiplatform 1.9.3 to 1.10.1. Kotlin remains 2.3.10 and Material3 remains the existing stable 1.9.0; no wallet data, engine pins, identities, migration logic or reservation rules change.

## Observed problem

Exact build-8 app tree b85a6cc32c31bdfcb6a3f60b1cf83dad5edba3ad has incomplete text glyphs in native run 42136946441 attempt 2 and corrected focused run 42170130823 attempt 1. All four focused frames show the same missing glyphs. The latter did pass all five native Lightning markers (Tor, onion peer, tower registration, empty SCB RPC, completed true). Its failure is visual, not a repeated recovery failure.

Evidence artifact 13482979158 SHA256 2476750d3589ade7ae1f019ac9ddb9bd339f85f7a74c8f09e58dbaae73960235 was verified against the API digest and ZIP CRC. No funded channels or financial transactions were used.

## Upstream basis

https://github.com/JetBrains/compose-multiplatform/releases/tag/v1.10.1 lists the iOS text-style cache fix (#2630) and corrupted font/icon fix (#2693, backport of #2684). Primary PR #2684 fixes the MetalRedrawer isActive setter comparison and includes UIKit rendering lifecycle tests. This matches a plausible rendering-layer cause, but is not yet proof of Kilowallet's root cause or of this candidate's success.

## Required validation

- Existing JVM/desktop migration, identity, scoped engine/backups and pending/frozen regression tests.
- Existing full native CI: executed iOS tests, all runtime markers, screenshot review and arm64 Release build.
- Inspect Lightning text after long native startup and on re-entry; full glyph completeness must pass, not only OCR headings or workflow status.
- Do not merge, sign, upload, advance immutable qualification pins or replace Apple's current review until this candidate qualifies. A product dependency change requires a new source/artifact/build receipt, not relabeling build 8.
- Prior cold-recovery timeout in run 42163150260 remains a separate unresolved incident. This UI update is not an LND engine fix.
