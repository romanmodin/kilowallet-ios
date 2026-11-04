# Signed-round safety work in progress

This isolated publisher successor strengthens reservation handling. It is not a qualified release.

- Persist signature intent and the produced signature before publishing it. An absent relay acknowledgement does not prove the signature was not received.
- Do not abort a locally signed round because the signing deadline expired or an abort message arrived.
- Do not infer a conflicting spend from an unseen transaction or a failed backend lookup after twelve hours.
- Retry broadcasting a complete plan while still in SIGNING after a broadcast failure.
- Keep BROADCAST inputs in the app coin-selection exclusion set until resolution.

Synthetic regression fixtures exercise the actual session ticker using an in-memory relay. Corrected baseline tests fail for three specific premature-abort cases; the unsigned expiry control passes. Initial fixture setup incorrectly reused the change address and was corrected; those initial failures are not evidence of the production bug.

Still required before release: six-confirmation resolution and explicit deep conflicting-spend proof; reorg monitoring/rebroadcast; recovery of previously persisted signed terminal states; restart/ack-loss/broadcast-failure tests; signature/state retention; exact-source migration/native qualification. Current conservative guards may retain reservations indefinitely when proof is unavailable. Never advertise full Android/server safety parity or v6 support from this patch.

The healthy RC3 native run remains on 529420d. Do not push this successor over it or reuse that run as proof for the changed source.
