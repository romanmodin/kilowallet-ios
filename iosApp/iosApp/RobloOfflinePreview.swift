#if DEBUG && targetEnvironment(simulator)
import SwiftUI
import WebKit
import CryptoKit

// Simulator-only compatibility probe. Never attach Bridge.shared to this page.
struct RobloOfflinePreview: UIViewRepresentable {
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

    final class Coordinator: NSObject, WKNavigationDelegate {
        var entry: URL?
        func webView(_ webView: WKWebView, decidePolicyFor action: WKNavigationAction,
                     decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
            guard let url = action.request.url, url.isFileURL,
                  action.targetFrame?.isMainFrame == true,
                  url.standardizedFileURL.path == entry?.standardizedFileURL.path else {
                decisionHandler(.cancel); return
            }
            decisionHandler(.allow)
        }
    }

    func makeCoordinator() -> Coordinator { Coordinator() }

    func makeUIView(context: Context) -> WKWebView {
        let config = WKWebViewConfiguration()
        config.websiteDataStore = .nonPersistent()
        let root = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("roblo-preview", isDirectory: true)
        installLocalWasm(in: config, root: root)
        let view = WKWebView(frame: .zero, configuration: config)
        let index = root.appendingPathComponent("index.html")
        let rules = #"[{"trigger":{"url-filter":".*"},"action":{"type":"block"}},{"trigger":{"url-filter":"^file:"},"action":{"type":"ignore-previous-rules"}},{"trigger":{"url-filter":"^data:"},"action":{"type":"ignore-previous-rules"}},{"trigger":{"url-filter":"^blob:"},"action":{"type":"ignore-previous-rules"}}]"#
        WKContentRuleListStore.default().compileContentRuleList(
            forIdentifier: "roblo-offline-v2", encodedContentRuleList: rules
        ) { list, error in
            guard error == nil, let list = list,
                  FileManager.default.fileExists(atPath: index.path) else {
                view.loadHTMLString("<p>Offline preview unavailable. Stage the pinned client first.</p>", baseURL: nil)
                return
            }
            context.coordinator.entry = index
            view.navigationDelegate = context.coordinator
            view.configuration.userContentController.add(list)
            view.loadFileURL(index, allowingReadAccessTo: root)
        }
        return view
    }

    func updateUIView(_ view: WKWebView, context: Context) {}
}
#endif
