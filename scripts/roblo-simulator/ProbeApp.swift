#if DEBUG && targetEnvironment(simulator)
import SwiftUI

// Deliberately links the preview view only. No wallet, engine, Keychain or signing bridge.
@main
struct ProbeApp: App {
    var body: some Scene {
        WindowGroup { RobloOfflinePreview() }
    }
}
#endif
