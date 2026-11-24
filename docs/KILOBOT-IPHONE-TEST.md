# Test Kilobot on an iPhone

This project runs the Kilowallet RoboSats offer browser from commit `1c4e029b38dab8aa791c267663f9001b3f60e917`. The copy uses the display name **Kilobot QA** and the bundle identifier `dev.kilowallet.tests.KilobotWallet`. Xcode’s Run action uses the Release configuration. The Lightning, Ark and Tor libraries are included.

## Install on a test iPhone

1. On a Mac with Xcode 26 or newer and JDK 25 installed, extract both ZIP files and open `iosApp/iosApp.xcodeproj`. The first build needs internet access for Gradle and Kotlin dependencies.
2. Pair an iPhone running iOS 17 or newer with Xcode and enable Developer Mode on that phone. Under **iosApp → Signing & Capabilities**, select your development team. Keep a separate test bundle identifier. If your team needs a different identifier, give this test app its own unique identifier.
3. Select the iPhone as the run destination and press **Run**. Confirm the app on the phone is named **Kilobot QA**. If setup asks for a wallet, create a fresh, unfunded test wallet. Keep existing seeds and backups out of this test project.

## Check public offer browsing

Open **Settings → RoboSats offers · Kilobot** and tap **Load public offers**. An offer list or **No public offers found** confirms that Kilobot answered. A connection error leaves this check inconclusive.

Tap **Refresh offers**, leave Settings while it is loading, then return and start another request. Check that the second request completes and its result stays on screen. Repeat once to confirm the shared Tor connection still serves a fresh request. The test needs no order, payment or channel change.

Record the iPhone model, iOS version, approximate time, first result, and result after returning to Settings. A successful install alone leaves offer browsing unverified.
