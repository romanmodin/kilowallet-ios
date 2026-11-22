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
            #if DEBUG && targetEnvironment(simulator)
            if ProcessInfo.processInfo.environment["KW_ROBLO_PREVIEW"] == "1" {
                if #available(iOS 17, *), ProcessInfo.processInfo.environment["KW_ROBLO_LIVE_BOOK"] == "1" {
                    RobloLiveBookPreview()
                } else {
                    RobloOfflinePreview()
                }
            } else {
                ComposeView().ignoresSafeArea(.all)
            }
            #else
            ComposeView().ignoresSafeArea(.all)
            #endif
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

#if DEBUG && targetEnvironment(simulator)
@available(iOS 17, *)
private struct RobloLiveBookPreview: View {
    @State private var book: RobloPublicBook?
    @State private var message = "Public offers · Kilobot"
    @State private var loading = false

    var body: some View {
        VStack {
            Text(message)
            Button("Load public offers over Tor") { Task { await loadBook() } }.disabled(loading)
            if let book { RobloOfflinePreview(book: book) }
        }.task {
            if ProcessInfo.processInfo.environment["KW_ROBLO_PROBE"] == "1" { await loadBook() }
        }
    }

    @MainActor private func loadBook() async {
        guard !loading else { return }
        loading = true
        defer { loading = false }
        book = nil
        message = "Connecting to Kilobot over Tor…"
        do {
            book = try await Self.loadSnapshot()
            message = "Public offers · Kilobot"
        } catch {
            message = "Kilobot unavailable. Try again."
        }
    }
    // Fixed stage names and numeric errors only; never persist URLs, offers or wallet data.
    private static func probeStage(_ stage: String) {
        guard ProcessInfo.processInfo.environment["KW_ROBLO_PROBE"] == "1",
              let doc = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first
        else { return }
        try? Data(stage.utf8).write(to: doc.appendingPathComponent("roblo-preview/probe-stage.txt"),
                                    options: .atomic)
    }

    // This dedicated simulator mode never constructs ComposeView or a wallet bridge.
    // Do not reuse or stop an engine started by another caller.
    private static func loadSnapshot() async throws -> RobloPublicBook {
        try await Task.detached(priority: .userInitiated) {
            probeStage("engine-idle-check")
            guard ArtiEngine.state() == 0 else { throw URLError(.cannotConnectToHost) }
            probeStage("allocate-port")
            let port = ArtiEngine.freePort()
            guard (1...65535).contains(port) else { throw URLError(.cannotConnectToHost) }
            let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
            probeStage("prepare-directory")
            try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
            defer { try? FileManager.default.removeItem(at: root) }
            probeStage("start-tor")
            guard ArtiEngine.start(dir: root.path, logPath: root.appendingPathComponent("tor.log").path,
                                   port: port) == nil else { throw URLError(.cannotConnectToHost) }
            defer { ArtiEngine.stop() }
            // A bound SOCKS listener can precede a usable Tor circuit by over a minute.
            probeStage("tor-bootstrap")
            for _ in 0..<120 {
                if ArtiEngine.state() != 1 { break }
                try await Task.sleep(nanoseconds: 1_000_000_000)
            }
            guard ArtiEngine.state() == 2 else { throw URLError(.timedOut) }
            probeStage("fetch-started")
            do {
                let snapshot = try await RobloPublicBook.fetch(.kilobot, torPort: port)
                probeStage("snapshot-validated")
                return snapshot
            } catch {
                let errorInfo = error as NSError
                let category = errorInfo.domain == NSURLErrorDomain ? "url" : "other"
                probeStage("fetch-failed-\(category)-\(errorInfo.code)")
                throw error
            }
        }.value
    }
}
#endif
