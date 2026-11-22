The standalone test app compiles Kilowallet’s preview view without linking wallet engines. Its opt-in build flag reads the reviewed client from RobloClient in the application bundle; there is no Documents fallback. The first launch opens the pinned local client in a fresh, nonpersistent WebKit store. A second launch supplies a synthetic order book from the host and exercises the client’s Buy/USD and EUR filters. Production builds have no preview entry point.

The host accepts a fixed coordinator and bounded public order JSON. An invalid snapshot is rejected in full. A page can read only the selected coordinator’s anonymous public book. It cannot ask native code for a URL, wallet data or a trade. The client’s original content rules and offline Content Security Policy remain in force. Changing the snapshot recreates the view and its ephemeral store. There is no live Tor transport in this preview.

To run the host-side checks with Swift 6 from the repository root:

```sh
swiftc -parse-as-library -swift-version 5 -D ROBLO_BOOK_TESTS \
  iosApp/iosApp/RobloPublicBook.swift scripts/roblo-simulator/BookChecks.swift -o /tmp/kw-check-public-book
/tmp/kw-check-public-book scripts/roblo-simulator/book-fixture.json /tmp/kw-test-book.js
```

The existing opt-in simulator job also checks the native view, the two filtered rows, and blocked read/write requests, then captures the displayed book. Only synthetic amounts are used.

To prepare the reviewed kw5 files for a future app resource folder, run
`python3 scripts/roblo-simulator/prepare-resources.py pack client.zip NEW_OUTPUT`.
The input is the pinned inner client archive. The command stages it offline,
applies the existing content policy, checks the complete file tree, and publishes
`NEW_OUTPUT/RobloClient` only after verification. Recheck a copied folder with
`python3 scripts/roblo-simulator/prepare-resources.py verify NEW_OUTPUT/RobloClient`.
No Xcode build phase, network access, or device feature is added by this step.

The opt-in check also builds an isolated arm64 executable against the iPhoneOS SDK, checks its Mach-O platform and verifies its packaged resource tree. The device product has no simulator fixture or test driver. Only the simulator launches the app; a successful cross-build is not a physical-device result or a TestFlight build.
