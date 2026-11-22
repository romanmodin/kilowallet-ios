#if (DEBUG && targetEnvironment(simulator)) || PREVIEW_TOR_TEST
import Foundation

// The simulator preview owns only engines that it successfully started.
enum PreviewTorSession {
    static func read<T>(
        start: () -> Bool, stop: () -> Void, idle: () -> Bool, cleanup: () -> Void,
        pause: () async throws -> Void = { try await Task.sleep(nanoseconds: 100_000_000) },
        fetch: () async throws -> T
    ) async throws -> T {
        guard start() else {
            // A failed start can still have a worker winding down.
            if idle() { cleanup() }
            throw URLError(.cannotConnectToHost)
        }
        let result: Result<T, Error>
        do { result = .success(try await fetch()) }
        catch { result = .failure(error) }

        stop()
        // Rust allows five seconds for runtime shutdown. Wait off the UI thread
        // and leave the directory intact if the worker doesn't exit in time.
        for _ in 0..<80 {
            if idle() { break }
            try await pause()
        }
        guard idle() else { throw URLError(.timedOut) }
        cleanup()
        return try result.get()
    }
}
#endif
