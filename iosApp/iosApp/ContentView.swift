import SwiftUI
import UIKit
import KilowalletApp

/// Hosts the Compose UI (shared Kotlin, `app/src/commonMain`) and hands it the Swift services.
struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController(bridge: Bridge.shared, demo: Self.demoScreen())
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}

    /// CI screenshot mode (the `KW_DEMO` environment variable), Debug builds only. Not a launch
    /// argument: LND (linked in) parses the process's arguments as its own flags and stops on any
    /// it doesn't know.
    static func demoScreen() -> String? {
        #if DEBUG
        #if targetEnvironment(simulator)
        // CI writes the case into this throwaway simulator's container before each launch.
        let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        if let name = try? String(contentsOf: docs.appendingPathComponent("kw-demo-case"), encoding: .utf8),
           !name.isEmpty {
            try? name.write(to: docs.appendingPathComponent("kw-demo-selected"), atomically: true, encoding: .utf8)
            return name
        }
        #endif
        if let s = ProcessInfo.processInfo.environment["KW_DEMO"], !s.isEmpty { return s }
        #endif
        return nil
    }
}

struct ContentView: View {
    @Environment(\.scenePhase) private var scenePhase
    var body: some View {
        ZStack {
            ComposeView().ignoresSafeArea(.all)
            if scenePhase != .active {
                Color.black.ignoresSafeArea()
                Text("Kilowallet").foregroundColor(.white)
            }
        }
        .background(Color.black)
        .onChange(of: scenePhase) { phase in
            if phase == .background { MainViewControllerKt.walletDidEnterBackground() }
        }
    }
}
