# Kilowallet for iPhone 1.0.0 — review information

This version upgrades the existing Kilowallet app. It manages Bitcoin on the BLAKE2b chain (XBT). It does not display or spend the user's on-chain SHA-256 Bitcoin balance. No account or sign-in is required.

To inspect the wallet without funding it, create an empty spending wallet. The home screen includes on-chain, Kilojoin, Lightning and Ark views. Alternatively, add the public BIP-84 test-vector account below as a watch-only wallet; this does not enable signing, Lightning or Ark. No reviewer needs to provide recovery words or make a purchase.

Public watch-only test key: `zpub6rFR7y4Q2AijBEqTUquhVz398htDFrtymD9xYYfG1m4wAcvPhXNfE3EfH1r1ADqtfSdVCToUG868RvUUkgDKf31mGDtKsAYz2oz2AGutZYs`

On-chain features include SegWit and Taproot, coin controls, external signing via PSBT, and fee bumping. Kilojoin is an optional collaborative on-chain transaction protocol; participants review and sign their own inputs. The Lightning tab runs a node on the iPhone. An optional bridge lets the user pay a SHA-256 Lightning invoice using the BLAKE2b network; the bridge operator handles the swap. Ark is an optional service operated separately by Paperclip Holdings LLC. Network functions require connectivity. Background notifications and Ark renewal run only when iOS permits them.

The wallet uses the device Keychain and local authentication. Users can deliberately export a password-protected backup containing recovery material and, where applicable, Ark data and a static Lightning channel backup. These exports can leave the device at the user's direction. Permissions include camera access for QR scanning, local notifications, and background refresh/processing.

## Publisher checks before submitting
- Use the exact qualified signed 1.0 build, actual device screenshots and the current App Store record. Supply only verified review/demo material. TestFlight approval is separate from public approval.
- Review the bridge, relay, watchtower, price and Ark disclosures in PRIVACY.md. Never reuse the 0.4 privacy responses without checking the 1.0 data flows.
- Non-exempt encryption is declared in the app. Check the actual compliance status for this build and applicable territories. Do not assert Apple has approved a declaration until it has.
- Historical 0.4.1 notes are retained separately; they do not describe this release.
