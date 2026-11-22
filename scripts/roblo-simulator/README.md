The standalone preview compiles the same Debug-only view used by the wallet, without linking wallet engines. The first launch opens the pinned local client in a fresh, nonpersistent WebKit store. A second launch supplies a synthetic order book from the host and exercises the client’s Buy/USD and EUR filters. Production builds have no preview entry point.

The host accepts a fixed coordinator and bounded public order JSON. An invalid snapshot is rejected in full. A page can read only the selected coordinator’s anonymous public book. It cannot ask native code for a URL, wallet data or a trade. The client’s original content rules and offline Content Security Policy remain in force. Changing the snapshot recreates the view and its ephemeral store. There is no live Tor transport in this preview.

To run the host-side checks with Swift 6 from the repository root:

```sh
swiftc -parse-as-library -swift-version 5 -D ROBLO_BOOK_TESTS \
  iosApp/iosApp/RobloPublicBook.swift scripts/roblo-simulator/BookChecks.swift -o /tmp/kw-check-public-book
/tmp/kw-check-public-book scripts/roblo-simulator/book-fixture.json /tmp/kw-test-book.js
```

The existing opt-in simulator job also checks the native view, the two filtered rows, and blocked read/write requests, then captures the displayed book. Only synthetic amounts are used.
