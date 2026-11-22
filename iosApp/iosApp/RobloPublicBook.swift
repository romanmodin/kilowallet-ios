#if KW_KILOBOT_DEVICE_QA || (DEBUG && targetEnvironment(simulator)) || ROBLO_BOOK_TESTS || KW_ROBLO_BUNDLED_PREVIEW
import Foundation

// A single snapshot of a public coordinator book. No wallet or HTTP client is accepted here.
struct RobloPublicBook: Hashable {
    enum Coordinator: String, CaseIterable, Hashable {
        case kilobot, marcanotrades, rosegarden

        // Pinned kw5 frontend/static/federation.json, mainnet onion public book endpoints.
        var endpoint: String {
            let host: String
            switch self {
            case .kilobot: host = "kiloblo3ibbey3wridofauizwb467y5osfaycxoecgsf66mggsrw6ryd"
            case .marcanotrades: host = "olwrnvnukon2lkgqmbdtjfm3vqzadhbtbjmi4abillzjmhwfhhp3mryd"
            case .rosegarden: host = "fvdl26sy3yquqp5ft5p6gkwf3utztghncqcz5vjg62cwsurr2yyjluid"
            }
            return "http://" + host + ".onion/api/book/"
        }
    }

    let coordinator: Coordinator
    private let encoded: String
    let offerSummary: String

    // Display only numeric identifiers and decimal strings. No nicknames, links or free text.
    private static func amount(_ value: Any?) -> String? {
        guard let text = value as? String, (1...24).contains(text.utf8.count) else { return nil }
        let parts = text.split(separator: ".", omittingEmptySubsequences: false)
        guard parts.count <= 2, parts.allSatisfy({ !$0.isEmpty && $0.utf8.allSatisfy { (48...57).contains($0) } })
        else { return nil }
        return text
    }

    private static func display(_ head: RowHead, _ row: [String: Any]) -> String {
        let currency = [1: "USD", 2: "EUR"][head.currency] ?? "Currency \(head.currency)"
        let prefix = "#\(head.id) · "
        // Never interpret a sats-denominated order as a fiat amount.
        if let value = row["sats_denominated"], (value as? Bool) != false { return prefix + currency }
        if head.has_range, let low = amount(row["min_amount"]), let high = amount(row["max_amount"]) {
            return prefix + low + "–" + high + " " + currency
        }
        if !head.has_range, let fixed = amount(row["amount"]) { return prefix + fixed + " " + currency }
        return prefix + currency
    }
    private static let limit = 262_144
    // PublicOrder fields actually consumed by the pinned client. Third-party links and
    // unknown extensions require review. No arbitrary object or private response is forwarded.
    private static let fields: Set<String> = [
        "id", "created_at", "expires_at", "type", "currency", "amount", "base_price",
        "has_range", "min_amount", "max_amount", "payment_method", "is_explicit", "premium",
        "satoshis", "satoshis_now", "latitude", "longitude", "bond_size", "escrow_type",
        "maker", "escrow_duration", "maker_nick", "maker_hash_id", "price", "fixed_price",
        "sats_denominated", "sats_amount", "sats_min_amount", "sats_max_amount", "maker_status",
        "coordinatorShortAlias", "federated"
    ]
    private struct RowHead: Decodable {
        let id: Int64
        let type: Int
        let currency: Int
        let has_range: Bool
        let payment_method: String
    }

    // The pinned coordinators return this precise 404 object for an empty market.
    // A missing route, proxy failure or other error must never look like an empty book.
    init?(coordinator: Coordinator, status: Int, response: Data) {
        switch status {
        case 200:
            self.init(coordinator: coordinator, publicJSON: response)
        case 404:
            guard response.count <= 1024,
                  let error = (try? JSONSerialization.jsonObject(with: response)) as? [String: String],
                  error == ["not_found": "No orders found, be the first to make one"] else { return nil }
            self.init(coordinator: coordinator, publicJSON: Data("[]".utf8))
        default: return nil
        }
    }

    init?(coordinator: Coordinator, publicJSON: Data) {
        guard publicJSON.count <= Self.limit,
              let rows = (try? JSONSerialization.jsonObject(with: publicJSON)) as? [[String: Any]],
              rows.count <= 500 else { return nil }
        for row in rows {
            guard row.keys.allSatisfy(Self.fields.contains) else { return nil }
            if let alias = row["coordinatorShortAlias"], (alias as? String) != coordinator.rawValue { return nil }
            for value in row.values {
                if let string = value as? String {
                    guard string.utf8.count <= 512 else { return nil }
                } else if !(value is NSNumber || value is NSNull) { return nil }
            }
        }
        guard let clean = try? JSONSerialization.data(withJSONObject: rows, options: [.sortedKeys]),
              clean.count <= Self.limit, let heads = try? JSONDecoder().decode([RowHead].self, from: clean)
        else { return nil }
        var ids = Set<Int64>()
        for h in heads {
            guard h.id > 0, h.id <= 9_007_199_254_740_991, ids.insert(h.id).inserted,
                  (h.type == 0 || h.type == 1), h.currency > 0 else { return nil }
        }
        if heads.isEmpty {
            offerSummary = "No public offers found."
        } else {
            var lines = zip(heads.prefix(20), rows.prefix(20)).map { Self.display($0.0, $0.1) }
            if heads.count > 20 { lines.append("Showing 20 of \(heads.count) offers.") }
            offerSummary = lines.joined(separator: "\n")
        }
        self.coordinator = coordinator
        encoded = clean.base64EncodedString()
    }

    // Both substitutions are safe literals: a compiled coordinator endpoint and Base64.
    // The page cannot ask native code for more data or choose a different destination.
    var documentStartScript: String {
        Self.adapter + "('" + coordinator.endpoint + "','" + encoded + "');"
    }

    private static let adapter = #"""
    ((endpoint, encoded) => {
      'use strict';
      if (location.protocol !== 'file:') return;
      const bytes = Uint8Array.from(atob(encoded), c => c.charCodeAt(0));
      const localFetch = window.fetch.bind(window);
      localStorage.setItem('settings_connection', 'api');
      window.fetch = async function(input, options) {
        const url = new URL(input instanceof Request ? input.url : input, document.baseURI);
        if (['file:', 'data:', 'blob:'].includes(url.protocol)) return localFetch(input, options);
        if (url.href !== endpoint) throw new TypeError('Not in the offline public book');
        const req = new Request(input, options);
        if (req.method !== 'GET' || req.body !== null || req.credentials === 'include' ||
            [...req.headers.keys()].some(k => k !== 'accept' && k !== 'content-type')) {
          throw new TypeError('Only anonymous public book reads are available');
        }
        if (req.signal.aborted) throw new DOMException('Aborted', 'AbortError');
        return new Response(bytes, {headers: {'Content-Type': 'application/json; charset=utf-8'}});
      };
    })
    """#
}
#endif

#if (KW_KILOBOT_DEVICE_QA || (DEBUG && targetEnvironment(simulator))) && !ROBLO_BOOK_TESTS
import Network

@available(iOS 17.0, *)
private final class RobloNoRedirect: NSObject, URLSessionTaskDelegate {
    func urlSession(_ session: URLSession, task: URLSessionTask,
                    willPerformHTTPRedirection response: HTTPURLResponse,
                    newRequest request: URLRequest,
                    completionHandler: @escaping (URLRequest?) -> Void) {
        completionHandler(nil)
    }
}

@available(iOS 17.0, *)
extension RobloPublicBook {
    static func fetch(_ coordinator: Coordinator, torPort: Int32) async throws -> Self {
        guard (1...65535).contains(torPort), let url = URL(string: coordinator.endpoint)
        else { throw URLError(.cannotConnectToHost) }
        let config = URLSessionConfiguration.ephemeral
        guard let port = NWEndpoint.Port(rawValue: UInt16(torPort)) else { throw URLError(.badURL) }
        var proxy = ProxyConfiguration(socksv5Proxy: .hostPort(host: "127.0.0.1", port: port))
        proxy.applyCredential(username: UUID().uuidString, password: "kilowallet")
        proxy.allowFailover = false
        config.proxyConfigurations = [proxy]
        config.httpShouldSetCookies = false
        config.urlCredentialStorage = nil
        config.urlCache = nil
        config.timeoutIntervalForRequest = 20
        config.timeoutIntervalForResource = 45
        let session = URLSession(configuration: config, delegate: RobloNoRedirect(), delegateQueue: nil)
        defer { session.invalidateAndCancel() }
        var request = URLRequest(url: url)
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        let (stream, response) = try await session.bytes(for: request)
        guard let http = response as? HTTPURLResponse, http.url == url,
              [200, 404].contains(http.statusCode) else { throw URLError(.badServerResponse) }
        let cap = http.statusCode == 404 ? 1024 : Self.limit
        guard response.expectedContentLength <= Int64(cap) else { throw URLError(.dataLengthExceedsMaximum) }
        var body = Data()
        for try await byte in stream {
            guard body.count < cap else { throw URLError(.dataLengthExceedsMaximum) }
            body.append(byte)
        }
        guard let book = Self(coordinator: coordinator, status: http.statusCode, response: body)
        else { throw URLError(.cannotParseResponse) }
        return book
    }
}
#endif
