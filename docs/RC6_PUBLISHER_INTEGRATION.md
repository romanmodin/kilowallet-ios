# RC6 publisher app integration

This branch ports upstream app commit `70f48ab6d2d50602f6cfdfcab8f86879c426b56d` onto the publisher's RC6 core and backup-durability fixes. It is a draft candidate, not an uploaded or device-qualified build.

The Send market, plan, progress and result screens use the existing RC6 core. Publisher adaptations retain captured wallet ID/account/server/pin and wallet generation across asynchronous work, wallet-specific seed loading, duplicate-unlock prevention, frozen and pending checks, mutual input reservations with Kilojoin, and persisted address high-water marks. Only the captured wallet's commitment store is used. Legacy global commitments are conservatively unioned without deleting their source. Backup history is validated and flushed for the restored account before seed/catalog changes.

The taker calls the application immediately before signing and before the first signed transaction handoff. The app rechecks policy at both boundaries and stores/flushed the exact signed transaction in the existing wallet pending store before a maker or backend can receive it. Successful and uncertain handoffs remain pending until the user checks/resends the same transaction through the existing recovery flow. Exceptions after persistence never claim that nothing was sent. Cancellation cleans up keys after session shutdown; no timeout releases a persisted payment.

Validation added: synthetic duplicate taps, captured seed identity, stale epoch/server callbacks, frozen inputs, pool/pending rejection, cancellation, persisted change reservation, legacy commitment migration and failed backup flushes; in-process directory tests reject changed policy and failed signed-transaction persistence before network handoff. No real transactions are authorized or performed.

Release gates remain: passing exact-source local and historical migration tests, native simulator/device builds and visual evidence, Gitar review, protected signing/upload receipts and exact TestFlight group eligibility. Keep the existing App Store build 15 review in place. No auto-merge for this payment/storage change.
