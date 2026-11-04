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
    def test_tls_rejection_preserved_not_called_successful_scan(self):
        result = runtime.check_engines(GOOD)
        self.assertIn("Error",result["electrumResult"][0])
        self.assertFalse(result["physicalPhoneTested"])
        self.assertFalse(result["fundedChannelRestoreTested"])
if __name__ == "__main__":
    unittest.main()
