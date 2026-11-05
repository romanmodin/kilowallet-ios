# Publisher RC4 integration

Upstream: Kilombino/kilowallet-ios v1.0.0-rc4,
378d22db8727661ee80e6850346abccf9e307a61 (2026-11-05).
Publisher base: 0b805d0494aa408f243e38d4474b9e0a03ffdfd5.

Integrates the three upstream changes since RC3, including v6 terms binding,
acknowledgments, snapshot recovery and saved rounds. Signing remains manual;
iOS background service and notifications are not included.

Preserves publisher wallet catalog/seed-slot migration, address progress,
coin control, frozen/pending guards, scoped LND/Ark data and backups, Tor
routing, certificate validation, engine pins and release qualification.
No replacement with upstream's unsigned IPA and no encryption declaration change.

Merge-specific safeguards:
- Any locally signed input survives a relay abort or deadline, across pool versions.
- Persist signed member state and v6 acknowledgment state before publishing.
- Keep the hash-checked, re-read chain-history conflict proof for v6 as well as legacy rounds.
- Revalidate the saved plan and every witness before rebroadcast.
- Recheck previously confirmed signed rounds on restart/foreground for a reorg.
- Keep all prior visual checks and add v6 pool and creation screens.

Validation must use synthetic wallets only. CI requires all 18 asynchronous
fault-injection cases and 9 reservation cases to execute and pass; in addition
it runs existing wallet regressions, simulator tests/screens and an unsigned
arm64 device build. The two optional KjLabInteropTest cases return without
network testing unless explicitly configured; those returns are not evidence
of joint-regtest interoperability. Physical iPhone qualification remains separate.

Release blocker: the prior 1.0 build 5 upload failed ITMS-90592. Determine the
supported encryption classification/questionnaire response before another
protected upload. Do not infer an exemption from upstream's Info.plist flag.
Keep the existing 0.4 App Store review in place until a replacement qualifies.
