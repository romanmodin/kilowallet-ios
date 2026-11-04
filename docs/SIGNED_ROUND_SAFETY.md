# Signed-round safety work in progress

This isolated publisher successor strengthens reservation handling. It is not a qualified release.

- Persist signature intent and the produced signature before publishing it. An absent relay acknowledgement does not prove the signature was not received.
- Do not abort a locally signed round because the signing deadline expired or an abort message arrived.
- Do not infer a conflicting spend from an unseen transaction or a failed backend lookup after twelve hours.
- Retry broadcasting a complete plan while still in SIGNING after a broadcast failure.
- Keep BROADCAST inputs in the app coin-selection exclusion set until resolution.

Synthetic regression fixtures exercise the actual session ticker using an in-memory relay. Corrected baseline tests fail for three specific premature-abort cases; the unsigned expiry control passes. Initial fixture setup incorrectly reused the change address and was corrected; those initial failures are not evidence of the production bug.

A broadcast now requires six confirmations from the wallet backend before the round is marked confirmed. On restoring saved wallet sessions, recheck signed terminal records before age-based pruning. A missing transaction can be rebroadcast only from a complete plan matching the saved transaction ID, passing the wallet's payment checks and signature verification for every input. A backend exception does not trigger rebroadcast.

A different spend must be found in the configured Electrum server's history for a round input and be six confirmations deep. The wallet checks the actual transaction bytes, exact input outpoint, and consistent history/depth/UTXO observations before releasing a reservation. Chain lookup failure is not a release condition. Confirmed rounds kept in memory are rechecked on returning to the pool screen and periodically; a verified drop below the threshold returns them to reserved status. These are backend observations, not independent block validation.

Still required before release: further restart/ack-loss/broadcast-failure tests, and signature/state retention; exact-source migration/native qualification. Current conservative guards may retain reservations indefinitely when proof is unavailable. Never advertise full Android/server safety parity or v6 support from this patch.

The healthy RC3 native run remains on 529420d. Do not push this successor over it or reuse that run as proof for the changed source.
