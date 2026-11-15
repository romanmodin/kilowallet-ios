# RC6 publisher integration checkpoint

Base: qualified publisher build 15, source 68148c8c5741c7b84b37f33322e67273f7208a19.
Upstream RC6: 6a07be1835dc650a6274a90eb0ffeb214b5310ef.

Integrated shared engine commit 7390751 and directory handshake fix 6a07be1. The app integration commit 70f48ab is deliberately not applied yet. No JoinBlaket payment UI is enabled by this branch.

Before app integration qualifies:
- Replace legacy seed loading with explicit wallet identity and epoch checks across authentication and async work.
- Scope commitments and backup import/export to the owning wallet.
- Reserve change addresses durably using the publisher address store.
- Apply frozen/pending policy before preparation and signing; retain signed/uncertain reservations across restart and cancellation.
- Prevent wallet switching/removal and concurrent sends/pools from invalidating an active round.
- Add synthetic regressions for these boundaries, then native qualification.

The initial local Gradle attempt did not execute tests: no Java on PATH/JAVA_HOME. Native workflow uses Temurin 25. No real funds, signing upload, or App Store review changes were made. Existing build 15 review must remain until a replacement qualifies.

GitHub release asset metadata matches supplied unsigned IPA SHA256 db8f1f8dfd4dca715b26f78a9a99e239dd603ee84535eedafd525f9e2d77aaf8 and archive SHA256 2f50259b75bae29c1400b0b603f72cded9a31a1827a2d513f60095fe93e95a00. These are metadata comparisons, not downloaded artifact verification.
