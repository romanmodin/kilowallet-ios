import SwiftUI
import UIKit
#if DEBUG && targetEnvironment(simulator)
import WebKit
import CryptoKit
#endif
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
                RobloOfflinePreview()
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
// Simulator-only compatibility probe. Never attach Bridge.shared to this page.
private struct RobloOfflinePreview: UIViewRepresentable {
    // WebKit can load a file URL as a script but cannot fetch the Wasm from that
    // file origin. Supply only the verified kw5 module; never grant extra file access.
    private func installLocalWasm(in config: WKWebViewConfiguration, root: URL) {
        let relative = "static/frontend/eae97d9d10706bb0d504.module.wasm"
        let file = root.appendingPathComponent(relative)
        guard file.resolvingSymlinksInPath().path == root.resolvingSymlinksInPath().appendingPathComponent(relative).path,
              let size = try? file.resourceValues(forKeys: [.fileSizeKey]).fileSize,
              size == 1_070_846,
              let bytes = try? Data(contentsOf: file),
              SHA256.hash(data: bytes).map({ String(format: "%02x", $0) }).joined()
                == "54793f368bf86db3930b7d91334b572f96debf0f13793731e7d66012b1e8b9e6"
        else { return }
        let script = """
        (() => {
          const target = new URL('./\(relative)', location.href).href;
          const original = window.fetch;
          window.fetch = function(input, options) {
            const url = new URL(input.url || input, document.baseURI);
            if (url.protocol === 'file:' && url.href === target &&
                (options?.method || input.method || 'GET') === 'GET') {
              const data = Uint8Array.from(atob('\(bytes.base64EncodedString())'), c => c.charCodeAt(0));
              return Promise.resolve(new Response(data, {headers: {'Content-Type':'application/wasm'}}));
            }
            return original.call(this, input, options);
          };
        })();
        """
        config.userContentController.addUserScript(WKUserScript(
            source: script, injectionTime: .atDocumentStart, forMainFrameOnly: true))
    }

    func makeUIView(context: Context) -> WKWebView {
        let config = WKWebViewConfiguration()
        config.websiteDataStore = .nonPersistent()
        let root = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("roblo-preview", isDirectory: true)
        installLocalWasm(in: config, root: root)
        let view = WKWebView(frame: .zero, configuration: config)
        let index = root.appendingPathComponent("index.html")
        let rules = #"[{"trigger":{"url-filter":".*"},"action":{"type":"block"}},{"trigger":{"url-filter":"^(file:|data:|blob:)"},"action":{"type":"ignore-previous-rules"}}]"#
        WKContentRuleListStore.default().compileContentRuleList(
            forIdentifier: "roblo-offline-v1", encodedContentRuleList: rules
        ) { list, error in
            guard error == nil, let list = list,
                  FileManager.default.fileExists(atPath: index.path) else {
                view.loadHTMLString("<p>Offline preview unavailable. Stage the pinned client first.</p>", baseURL: nil)
                return
            }
            view.configuration.userContentController.add(list)
            view.loadFileURL(index, allowingReadAccessTo: root)
        }
        return view
    }

    func updateUIView(_ view: WKWebView, context: Context) {}
}
#endif
