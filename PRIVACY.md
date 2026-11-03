# Kilowallet privacy information

Kilowallet 1.0 iPhone release candidate, November 3, 2026. Original creator: **Kilombino**. Distribution maintained by romanmodin.

## Data on your iPhone

Recovery words and an optional BIP-39 passphrase are kept in a separate device-only Keychain item for each hot wallet. Watch-only wallets do not store recovery words. Real-device reads require Face ID, Touch ID or the device passcode. Wallet names, the selected wallet, transaction labels/notes, saved recipient names/addresses, frozen output IDs, backup-check timestamps, display units, the hide-amounts preference, public account keys, shared server settings, accepted certificate fingerprints, receive/change-address progress and an unresolved signed transaction are stored locally in app preferences. A signed transaction is public transaction data, not a recovery secret. Local preferences may be included in operating-system backups; the recovery Keychain item is marked device-only.

Recovery words are brought into memory when you choose to reveal them, sign, or authenticate a written-backup check. The written-backup check derives the public account locally from the entered words and passphrase; it stores only the successful-check timestamp, not those entries. The app obscures its interface while inactive and dismisses secret screens in the background. These controls do not guarantee that all screenshots, screen recording, memory copies or a compromised device can be prevented.

## Electrum servers and public transactions

The app connects to your selected Electrum server over TLS. The bundled default is `fulcrum.kilombino.com:17717`. On a direct connection that server receives your network address. It receives queried address script hashes and wallet-history requests, and any transaction you submit. These queries can be linked to your wallet. The server is operated separately from this distribution; the default operator reports no IP or query logs, except that failed requests may log a script hash without an IP until daily deletion. Other servers set their own policies. You can choose your own compatible server in Settings.

Transactions broadcast to the network can become public blockchain records. Removing a wallet from the app does not erase blockchain records or data previously received by a server.

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

## Optional network services in 1.0

Kilojoin uses a relay to coordinate collaborative transactions. The relay operator reports no IP logs and deletes retained coordination messages after seven days. Public pool announcements are visible to other participants. This build makes no promise that all Kilojoin traffic uses Tor. The relay and price endpoint use Cloudflare, which processes connection data. The price endpoint operator reports no access logs.

The Lightning bridge is optional. This iPhone release candidate contacts the bridge via HTTPS through Cloudflare, even if the Lightning node uses Tor. The operator keeps IPs only in memory for rate limiting until an hour of inactivity. Quotes retain the payment hash, amount and expiry until expiry plus one minute; proof tokens last one hour and the re-quote guard about eleven minutes. The bridge's Lightning node retains its swap journal indefinitely, including payment hash, destination invoice (containing the recipient's node ID), amounts, preimage and state. An internal monitor retains hashes and states for operator alerts. There is no payer account; the operator reports it does not receive the payer's node ID.

The operator's Lightning watchtower is onion-only. It stores a session public key, encrypted breach-remedy records and a short breach identifier until the client deletes the session after its channels close. The operator reports that it does not receive client IPs, invoices or payment hashes from the watchtower protocol.

The default Ark server, ark.paperclippool.xyz, is operated by Paperclip Holdings LLC. Its disclosed data types include IP logs, public keys, VTXO identifiers, invoices, hashes, amounts and recovery data. Retention depends on purpose; signing and recovery records can be long-lived. See [Paperclip's privacy notice](https://pool.paperclippool.xyz/privacy) and [terms](https://pool.paperclippool.xyz/terms), or contact contact@paperclippool.xyz.

## Wallet and node backups

A password-protected .kab backup is distinct from a CSV export. It contains wallet recovery material and can include Ark data and a static Lightning channel backup. A separate Lightning move file can contain a live node's channel state. Anyone with the file and its password may be able to access funds. Backups saved or shared by the user are outside the app's control. Removing an on-chain wallet is not proof that all native node files, local copies or remote records were erased.
