import SwiftUI

@main
struct iOSApp: App {
    init() {
        // Must be registered before launch ends: iOS's background run to renew Ark coins.
        #if !KW_KILOBOT_DEVICE_QA
        ArkReminder.register()
        BalanceWatch.register()
        #endif
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
                .preferredColorScheme(.dark)
        }
    }
}
