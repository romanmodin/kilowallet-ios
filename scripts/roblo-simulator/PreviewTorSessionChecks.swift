import Foundation

@main enum PreviewTorSessionChecks {
    struct ReadFailed: Error {}
    static func main() async throws {
        var busy = false, stops = 0, cleanups = 0
        // Retry immediately after a failed fetch; start must see a released engine.
        for fail in [true, false] {
            var ticks = 0
            do {
                let value = try await PreviewTorSession.read(
                    start: { precondition(!busy); busy = true; return true },
                    stop: { stops += 1 }, idle: { !busy },
                    cleanup: { precondition(!busy); cleanups += 1 },
                    pause: { ticks += 1; if ticks == 3 { busy = false } }
                ) { () throws -> Int in if fail { throw ReadFailed() }; return 7 }
                precondition(!fail && value == 7)
            } catch is ReadFailed { precondition(fail) }
            precondition(ticks == 3 && !busy)
        }
        precondition(stops == 2 && cleanups == 2)
        // A still-running engine retains its directory, even after the deadline.
        do {
            _ = try await PreviewTorSession.read(start: { true }, stop: { stops += 1 },
                idle: { false }, cleanup: { fatalError() }, pause: {}, fetch: { 9 })
            fatalError()
        } catch let e as URLError { precondition(e.code == .timedOut) }
        // Failing to acquire an engine never grants permission to stop it.
        for idle in [true, false] {
            var removed = false
            do {
                _ = try await PreviewTorSession.read(start: { false }, stop: { fatalError() },
                    idle: { idle }, cleanup: { removed = true }, fetch: { () -> Int in fatalError() })
                fatalError()
            } catch let e as URLError { precondition(e.code == .cannotConnectToHost && removed == idle) }
        }
        print("Preview shutdown, immediate retry, timeout and failed acquisition: PASS")
    }
}
