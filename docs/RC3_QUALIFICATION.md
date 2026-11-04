# RC3 qualification scope — November 4, 2026

This publisher candidate integrates upstream `v1.0.0-rc3` at
`0ec864d669dc64ab4353c1be57e2deb3b418b7d3` into the wallet-preserving
publisher branch. It is not a claim of TestFlight or public availability.

## Included

- CoinJoin relay onion transport using Network.framework and the app's Tor SOCKS proxy on iOS 17+.
- Public wss relay fallback still routed through Tor; a failed Tor route does not fall back directly.
- Connection isolation keys retained for each seat/output; direct connections when Tor is off.
- Existing publisher wallet identity, address reservation, engine storage, authentication, pending/frozen, and balance-watch scheduling changes retained.
- Runtime evidence must show an actual pool-list response via onion or wss over Tor, even when zero pools are open. A direct route or error is not qualification.
- Synthetic discovery tests wait for the creator announcement and completion of the initial listing before requesting a fresh list. OPEN is persisted before announcement and refresh is ignored while a list is loading.

The unsigned upstream IPA/archive are not the publisher build. Their published API SHA-256 metadata is recorded separately; do not treat it as verification of downloaded bytes or our merged source.

## Protocol safety follow-up

The maintainer's November 4 Android/server fixes are not in this iPhone tag.
Direct source inspection found `PoolSession` still confirms at one confirmation,
aborts an unseen broadcast after 12 hours, and can abort on a failed broadcast.
RC3 does not implement v6, six-deep conflicting-spend proof, reorg recovery, or
24-hour signature/state refresh. Do not advertise those guarantees or reuse
Android/regtest results as evidence for this iPhone candidate. Review and qualify
these signed-input lifetime rules before approving this candidate for release.
Neither elapsed time nor a missing transaction proves a safely resolved signed round.

## Maintainer's proposed isolated interoperability environment

Supplied by the maintainer, not independently exercised by this candidate:
- Host: `t76ao4jdgi6vk5fhmzmonfoj74edkmbmhte43qxdus6xkzrxa6vgn4id.onion`.
- Faucet HTTP port 80; Nostr relay ws port 7777.
- Electrum TCP 50001 or TLS 50002. Supplied DER SHA-256:
  `2b4726292126958456d41fa2fa1c460c1181026ae37e112db2a11edb97bb6df5`.
- Announcement network `blake2b-regtest`; BLAKE2b from height 50,
  164-byte headers thereafter, bcrt1 addresses.
- Synthetic 100,000-sat asynchronous pools: long 60/30/120-minute vote/output/sign
  windows; short 10/5/20 minutes.

This source is mainnet v1–v5. It must not be pointed at those v6 pools and described
as compatible. A separate regtest build must explicitly bind chain, addresses,
network and TLS pin, use only synthetic coins, and never fall back to mainnet or
the public relay. Creator restarts and retention experiments require coordination;
none have been requested or performed by this integration.

Known maintainer issues to retain in future v6 testing: an OPEN member can leave at
pool timeout before a replacement vote arrives; the RPC server may misclassify an
orphaned funding transaction as a six-deep conflict. These are maintainer reports,
not independently reproduced iPhone failures.
