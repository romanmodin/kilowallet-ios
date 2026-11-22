#if DEBUG && targetEnvironment(simulator)
import Foundation
import UIKit
import WebKit

// Only compiled into the standalone simulator test app. No script message handlers.
@MainActor
enum ProbeBook {
    private struct ProbeFailure: Error { let stage: String }

    private static func findWebView(_ view: UIView) -> WKWebView? {
        if let web = view as? WKWebView { return web }
        for child in view.subviews {
            if let web = findWebView(child) { return web }
        }
        return nil
    }

    private static func waitForView() async throws -> WKWebView {
        for _ in 0..<150 {
            for case let scene as UIWindowScene in UIApplication.shared.connectedScenes {
                for window in scene.windows where !window.isHidden {
                    if let web = findWebView(window), web.url?.isFileURL == true,
                       web.url?.lastPathComponent == "index.html", !web.isLoading { return web }
                }
            }
            try await Task.sleep(nanoseconds: 200_000_000)
        }
        throw ProbeFailure(stage: "local web view")
    }

    private static func script(_ name: String) throws -> String {
        guard let url = Bundle.main.url(forResource: name, withExtension: "js") else {
            throw ProbeFailure(stage: "missing test resource")
        }
        return try String(contentsOf: url, encoding: .utf8)
    }

    static func runIfRequested() async {
        guard ProcessInfo.processInfo.arguments.contains("--offline-book-probe") else { return }
        let receipt = FileManager.default.temporaryDirectory.appendingPathComponent("kw-offers-result.json")
        var stage = "initial view"
        var report: [String: Any]
        do {
            let web = try await waitForView()
            guard !web.configuration.websiteDataStore.isPersistent else {
                throw ProbeFailure(stage: "persistent store")
            }
            stage = "local fixture reload"
            let fixture = try script("book-fixture")
            web.configuration.userContentController.addUserScript(WKUserScript(
                source: fixture, injectionTime: .atDocumentStart, forMainFrameOnly: true))
            web.reload()
            var ready = false
            for _ in 0..<150 {
                if let ok = try? await web.evaluateJavaScript(
                    "document.readyState === 'complete' && typeof document.kwOfflineBookReads === 'number'"
                ) as? Bool, ok { ready = true; break }
                try await Task.sleep(nanoseconds: 200_000_000)
            }
            guard ready else { throw ProbeFailure(stage: stage) }
            stage = "search and filter"
            // Separate script world; reads the rendered client DOM and operates only search controls.
            guard let matches = try await web.callAsyncJavaScript(try script("book-search"),
                arguments: [:], in: nil, contentWorld: .defaultClient) as? [String: Any],
                  let reads = try await web.evaluateJavaScript("document.kwOfflineBookReads") as? Int,
                  reads > 0 else { throw ProbeFailure(stage: stage) }
            report = ["version": 1, "fixtureReads": reads, "matches": matches]
        } catch {
            // Don't serialize WebKit error dictionaries or any page data into workflow logs.
            report = ["failedStage": (error as? ProbeFailure)?.stage ?? stage]
        }
        do {
            let data = try JSONSerialization.data(withJSONObject: report, options: [.sortedKeys])
            try data.write(to: receipt, options: .atomic)
        } catch { return }
    }
}
#endif
