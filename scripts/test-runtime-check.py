"""Synthetic evidence only: reject partial or failed native-engine results."""
import importlib.util
import unittest
from pathlib import Path
spec = importlib.util.spec_from_file_location("runtime", Path(__file__).with_name("check-runtime.py"))
runtime = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runtime)
GOOD = """
KW ark-live: backup round trip: file 400 bytes, db 300 bytes, wal 0, derived true; after restore: ArkModel.View.Ready
KW ln-live: node running, Tor in LND: true, SOCKS 9001
KW ln-live: connect Kilombino by onion: OK; peers {public=test.onion:9735}
KW ln-live: towers [(public, [test.onion:9911], true)]
KW ln-live: own channel backup 45 bytes; RestoreChannelBackups by RPC: OK
KW tor-live: pool list through Tor via onion: 0 pool(s)
KW tor-live: Tor SOCKS port 9001 
KW tor-live: Electrum through Tor: Error(message=Unapproved certificate)
"""
class RuntimeEvidenceTest(unittest.TestCase):
    def test_complete_results(self):
        self.assertTrue(runtime.check_engines(GOOD)["passed"])
    def test_each_missing_result_rejected(self):
        for line in GOOD.strip().splitlines():
            with self.subTest(line=line):
                self.assertFalse(runtime.check_engines(GOOD.replace(line, ""))["passed"])
    def test_starting_server_rejected(self):
        self.assertFalse(runtime.check_engines(GOOD.replace("onion: OK", "onion: server is still starting"))["passed"])
    def test_backup_rpc_failure_rejected(self):
        self.assertFalse(runtime.check_engines(GOOD.replace("RPC: OK", "RPC: server is still starting"))["passed"])
    def test_tor_disabled_rejected(self):
        self.assertFalse(runtime.check_engines(GOOD.replace("Tor in LND: true", "Tor in LND: false"))["passed"])
    def test_empty_towers_rejected(self):
        self.assertFalse(runtime.check_engines(GOOD.replace("[(public, [test.onion:9911], true)]", "[]"))["passed"])
    def test_failed_ark_restore_rejected(self):
        self.assertFalse(runtime.check_engines(GOOD.replace("ArkModel.View.Ready", "ArkModel.View.Failed"))["passed"])
    def test_actual_tor_sync_timeout_is_not_native_success(self):
        # Exact diagnostic markers from failed run 41792640577. GetInfo and an
        # exportable empty SCB are insufficient while the main RPC still starts.
        failed = (Path(__file__).parent / "fixtures" / "ln-startup-41792640577.txt").read_text()
        others = "\n".join(line for line in GOOD.splitlines() if "KW ln-live:" not in line)
        result = runtime.check_engines(others + "\n" + failed)
        self.assertFalse(result["passed"])
        for name in ("onionPeerConnected", "watchtowerRegistered", "emptyChannelBackupRpc"):
            self.assertFalse(result["checks"][name], name)
        self.assertTrue(result["checks"]["lightningUsesTor"])
        self.assertTrue(result["checks"]["arkBackupRestored"])

    def test_onion_retry_and_ip_peer_are_not_success(self):
        for marker in [
            "KW ln-live: onion attempt 1 not verified; peers {}",
            "KW ln-live: connect Kilombino by onion: OK; peers {public=213.152.161.201:50002}",
            "KW ln-live: connect Kilombino by onion: onion connection not verified; peers {}",
        ]:
            others = "\n".join(line for line in GOOD.splitlines() if "connect Kilombino" not in line)
            self.assertFalse(runtime.check_engines(others + "\n" + marker)["passed"])

    def test_relay_wss_fallback_still_requires_tor(self):
        self.assertTrue(runtime.check_engines(GOOD.replace("via onion:", "via wss over Tor:"))["passed"])

    def test_relay_direct_route_is_not_tor_success(self):
        self.assertFalse(runtime.check_engines(GOOD.replace("via onion:", "via direct:"))["passed"])

    def test_relay_failure_is_not_a_pool_count(self):
        self.assertFalse(runtime.check_engines(GOOD.replace("0 pool(s)", "failed: relay unavailable"))["passed"])

    def test_nonempty_relay_pool_count(self):
        self.assertTrue(runtime.check_engines(GOOD.replace("0 pool(s)", "12 pool(s)"))["passed"])

    def test_tls_rejection_preserved_not_called_successful_scan(self):
        result = runtime.check_engines(GOOD)
        self.assertIn("Error",result["electrumResult"][0])
        self.assertFalse(result["physicalPhoneTested"])
        self.assertFalse(result["fundedChannelRestoreTested"])
if __name__ == "__main__":
    unittest.main()
