#if (DEBUG && targetEnvironment(simulator)) || ROBLO_BOOK_TESTS
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
