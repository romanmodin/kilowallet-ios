# Kilowallet privacy information

Privacy policy for Kilowallet for iPhone, version 0.4.0. Updated November 1, 2026. Original creator: **Kilombino**. Distribution maintained by romanmodin.

## Data on your iPhone

Recovery words and an optional BIP-39 passphrase are kept in a separate device-only Keychain item for each hot wallet. Watch-only wallets do not store recovery words. Real-device reads require Face ID, Touch ID or the device passcode. Wallet names, the selected wallet, transaction labels/notes, saved recipient names/addresses, frozen output IDs, backup-check timestamps, display units, the hide-amounts preference, public account keys, shared server settings, accepted certificate fingerprints, receive/change-address progress and an unresolved signed transaction are stored locally in app preferences. A signed transaction is public transaction data, not a recovery secret. Local preferences may be included in operating-system backups; the recovery Keychain item is marked device-only.

Recovery words are brought into memory when you choose to reveal them, sign, or authenticate a written-backup check. The written-backup check derives the public account locally from the entered words and passphrase; it stores only the successful-check timestamp, not those entries. The app obscures its interface while inactive and dismisses secret screens in the background. These controls do not guarantee that all screenshots, screen recording, memory copies or a compromised device can be prevented.

## Electrum servers and public transactions

The app connects to your selected Electrum server over TLS. The bundled default is `fulcrum.kilombino.com:17717`. That server receives your network address, queried address script hashes and wallet-history requests, and any transaction you submit. These queries can be linked to your wallet. The server is operated separately from this distribution; the operator reports that neither the server nor its proxy logs client IP addresses. Queries are not stored in normal operation. Failed requests may leave a queried script hash in a debug log without an IP address; these logs are wiped daily. You can choose your own compatible server in Settings.

Transactions broadcast to the network can become public blockchain records. Removing a wallet from the app does not erase blockchain records or data previously received by a server.

## Kilojoin pools

When you use pool discovery or participation, the wallet connects to `wss://relay.kilombino.com`. The relay can see your network address, connection times and public pool announcements. During participation, encrypted protocol messages pass through the relay. Encryption does not hide network metadata or prevent participants from observing information needed to construct and verify the joint transaction. A broadcast transaction can become a permanent public record. CoinJoin does not guarantee anonymity.

## Optional exchange rates

If you enable fiat prices, the app requests a public market-price feed from `mempool.kilombino.com` to estimate the value displayed for your balance. The server receives your network address when the request is made. Turning fiat display off stops these price requests. The price-feed operator reports that its origin keeps no access logs.

## Service retention and infrastructure

The relay operator reports that it does not log client IP addresses. It retains encrypted round coordination messages for up to seven days, then deletes them automatically. Per-pool keys and blinded outputs limit linkage. This does not make blockchain transactions anonymous. Cloudflare processes connection data for relay and price-feed traffic under its own privacy policy; the operator reports it cannot access Cloudflare's logs. The operator reports no tracking, profiling, advertising or data sales and says legacy system logs from earlier configurations expire within a few weeks. These statements describe the default services, not a custom server you choose.

## Contact

For privacy questions about this distribution, use the project support page at https://github.com/romanmodin/kilowallet-ios/issues. Issues are public: never post recovery words, private keys, wallet addresses, transaction history or identifying details.

## Camera, clipboard and external links

Camera access is requested for QR scanning. The reviewed app decodes QR content locally; no image-upload path was identified. Paste/copy actions use the system clipboard. Avoid copying recovery secrets to the clipboard. Explorer and website links open only after you tap them; those sites apply their own privacy practices.

## Analytics and deletion

No first-party analytics, advertising or crash-reporting endpoint was identified in the reviewed app source. This statement does not cover operating-system diagnostics or independent server/website behavior. Removing a wallet deletes its Keychain secret, transaction labels/notes, saved recipients, frozen output policy, backup-check time and active wallet identity; retained server pins and per-wallet public progress/pending-transaction records support safe recovery and are not a promise of full local-data erasure. Back up your recovery words before removal.

Source, issue-reporting guidance and updates are available at https://github.com/romanmodin/kilowallet-ios . Do not include recovery words, passphrases, private keys or other secrets in a public report.

## Optional history exports

Labels and notes stay in local preferences unless you explicitly export them. They may be
included in operating-system backups, but recovery words alone do not restore them. CSV export
includes the selected wallet name, loaded transaction history, amounts, labels/notes and the
snapshot time. Hidden amounts are included after an explicit export confirmation. A protected
temporary file is passed to the iOS share sheet; you choose the recipient or storage location.
The app deletes its temporary copy after sharing/cancellation, or on next launch if interrupted.
Copies saved or sent by you are outside the app's control. Exports do not contain recovery
words, passphrases or public account keys. The last successful sync time is session-only.

## Optional payment-request sharing

Receive's share action presents the system share sheet with a public address/payment URI and
its QR image, including an amount when requested. You choose the destination; it may retain
the shared data. This action does not include wallet names, history, account keys or recovery
secrets. The shared text identifies XBT BLAKE2b; another wallet may ignore a URI chain label.
