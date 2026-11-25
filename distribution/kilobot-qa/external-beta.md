# Kilobot QA 1.0.0 (6): external TestFlight candidate

Candidate bundle: dev.kilowallet.tests.KilobotWallet. The existing iPhone project is pinned to the same SHA-256 as internal build 5. Only the archive build number changes. A successful unsigned archive does not create an external beta or a public invitation.

Beta description: Browse public RoboSats offers over Tor on iPhone. This beta displays public offers; it does not post orders, accept trades, make payments, or sign wallet transactions.

What to test: Use a new synthetic wallet with no funds. In wallet Settings, below Manage wallets, open RoboSats offers · Kilobot. Tap Load public offers. Check that you can distinguish an empty order book from an unavailable service. Refresh, close Settings during loading, reopen, and repeat. Do not import an existing wallet or share a seed.

Review steps: Use the separate Kilobot QA app. Follow the test steps above. The offers screen is read-only, and availability depends on the Tor connection and the coordinator. No funds or trades are required.

Distribution: Verify the exact unsigned artifact and recheck the build number against Apple before protected signing. Upload for external TestFlight eligibility, using the QA bundle only. Confirm feedback and review contact information with the publisher record. Add the build to a Kilobot QA external group and submit for TestFlight App Review. Only share the Apple-issued public invitation after this build is available to external testers.
