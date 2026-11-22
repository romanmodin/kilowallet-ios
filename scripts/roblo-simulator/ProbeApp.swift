#if KW_ROBLO_BUNDLED_PREVIEW
import SwiftUI

// Deliberately links the preview view only. No wallet, engine, Keychain or signing bridge.
@main
struct ProbeApp: App {
    var body: some Scene {
        WindowGroup {
            #if DEBUG && targetEnvironment(simulator)
            RobloOfflinePreview(book: ProbeBook.fixture).task { await ProbeBook.runIfRequested() }
            #else
            RobloOfflinePreview()
            #endif
        }
    }
}
#endif
