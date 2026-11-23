import Foundation

// Tests the same native snapshot initializer as the wallet offers panel.
@main
enum BookChecks {
    struct Failure: Error { let detail: String }
    static func main() throws {
        guard CommandLine.arguments.count == 2 else { throw Failure(detail: "input fixture path required") }
        let input = try Data(contentsOf: URL(fileURLWithPath: CommandLine.arguments[1]))
        guard let rows = (try JSONSerialization.jsonObject(with: input)) as? [[String: Any]],
              rows.count == 3, let book = RobloPublicBook(coordinator: .kilobot, publicJSON: input)
        else { throw Failure(detail: "valid public fixture rejected") }
        func snapshot(_ value: Any) throws -> RobloPublicBook? {
            RobloPublicBook(coordinator: .kilobot, publicJSON: try JSONSerialization.data(withJSONObject: value))
        }
        func reject(_ name: String, _ value: Any) throws {
            if try snapshot(value) != nil { throw Failure(detail: "accepted " + name) }
        }
        let empty: [[String: Any]] = []
        guard try snapshot(empty) != nil else { throw Failure(detail: "empty public book rejected") }
        let noOrders = Data(#"{"not_found":"No orders found, be the first to make one"}"#.utf8)
        func reply(_ status: Int, _ data: Data) -> RobloPublicBook? {
            RobloPublicBook(coordinator: .kilobot, status: status, response: data)
        }
        guard let market = reply(404, noOrders), market == (try snapshot(empty)),
              reply(200, input) == book, reply(503, noOrders) == nil,
              reply(404, Data("not found".utf8)) == nil, reply(404, input) == nil,
              reply(200, noOrders) == nil, reply(302, input) == nil else {
            throw Failure(detail: "could not distinguish empty market from unavailable coordinator")
        }
        guard book.offerSummary == "#910001 · 73 USD\n#910002 · 85 USD\n#910003 · 96 EUR",
              market.offerSummary == "No public offers found." else { throw Failure(detail: "offer display") }
        var row = rows[0]
        row["amount"] = "73 USD\nspoofed"
        guard try snapshot([row])?.offerSummary == "#910001 · USD" else { throw Failure(detail: "untrusted display") }
        row = rows[0]; row["sats_denominated"] = true
        guard try snapshot([row])?.offerSummary == "#910001 · USD" else { throw Failure(detail: "sats labeled fiat") }
        row = rows[0]; row["has_range"] = true; row["min_amount"] = "10"; row["max_amount"] = "25.50"
        guard try snapshot([row])?.offerSummary == "#910001 · 10–25.50 USD" else { throw Failure(detail: "range display") }
        row = rows[0]
        row["sample_private_field"] = "synthetic value"
        try reject("private field", [row])
        row = rows[0]; row["maker_nick"] = ["nested": "synthetic"]
        try reject("nested object", [row])
        row = rows[0]; row["payment_method"] = String(repeating: "x", count: 513)
        try reject("oversize text", [row])
        row = rows[0]; row["coordinatorShortAlias"] = "rosegarden"
        try reject("different coordinator", [row])
        try reject("duplicate ids", [rows[0], rows[0]])
        row = rows[0]; row["id"] = true
        try reject("boolean id", [row])
        row = rows[0]; row["type"] = 7
        try reject("unsupported order type", [row])
        try reject("wrapped response", ["book": rows])
        try reject("too many rows", (1...501).map { id -> [String: Any] in var r=rows[0]; r["id"]=id; return r })
        guard RobloPublicBook(coordinator: .kilobot, publicJSON: Data(repeating: 32, count: 262_145)) == nil else {
            throw Failure(detail: "oversize response")
        }
        print("Public book display, coordinator errors, invalid rows and size bounds: PASS")
    }
}
