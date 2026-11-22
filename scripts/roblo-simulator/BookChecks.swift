import Foundation

// Tests the same native snapshot initializer as the standalone iPhone preview.
@main
enum BookChecks {
    struct Failure: Error { let detail: String }
    static func main() throws {
        guard CommandLine.arguments.count == 3 else { throw Failure(detail: "input and output paths required") }
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
        var row = rows[0]
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
        let marker = "</script><script>syntheticPreviewMarker</script>"
        row = rows[0]; row["payment_method"] = marker
        guard let quoted = try snapshot([row]), !quoted.documentStartScript.contains(marker) else {
            throw Failure(detail: "row text embedded as script source")
        }
        for market in RobloPublicBook.Coordinator.allCases {
            guard let scoped = RobloPublicBook(coordinator: market, publicJSON: input),
                  scoped.documentStartScript.contains(market.endpoint) else {
                throw Failure(detail: "coordinator not bound to its public endpoint")
            }
        }
        try book.documentStartScript.write(to: URL(fileURLWithPath: CommandLine.arguments[2]), atomically: true, encoding: .utf8)
        print("Native public snapshot accepted empty and three-offer books; rejected invalid, private, foreign, duplicate and oversized data.")
    }
}
