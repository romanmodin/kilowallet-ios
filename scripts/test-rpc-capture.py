"""Guard the single-phrase/optional-QR CLI contract used by screenshot captures."""
import re, subprocess, unittest
from pathlib import Path
SCRIPT = Path(__file__).with_name("ci-screenshots.sh").read_text()
FUNCTION = re.search(r"    check_rendered\(\) \{.*?\n    \}", SCRIPT, re.S).group()
class CaptureContract(unittest.TestCase):
    def run_case(self, screen, phrases, fail=""):
        import shlex
        args=["image.png",phrases[0]]
        if screen=="request": args.append("?chain=xbt&amount=0.00120000")
        stub='checker() { printf "%s\\n" "$*"; [ "$#" -ge 2 ] && [ "$#" -le 3 ] || return 2; [ "$2" != "$FAIL" ] || return 1; }\n'
        code=stub+FUNCTION+'\nCHECKER=checker\nSHOT=image.png\nscreen='+shlex.quote(screen)+'\nFAIL='+shlex.quote(fail)+'\ncheck_args=('+ ' '.join(map(shlex.quote,args))+')\ncheck_rendered'
        return subprocess.run(["bash","-c",code],capture_output=True,text=True)
    def test_rpc_settings_all_phrases(self):
        r=self.run_case("rpc-settings",["Or your own node by RPC"])
        self.assertEqual(r.returncode,0)
        self.assertEqual(r.stdout.splitlines(),["image.png Or your own node by RPC","image.png Read BTC from my node","image.png node.invalid"])
    def test_rpc_certificate_all_phrases(self):
        r=self.run_case("rpc-cert",["Is this your node"])
        self.assertEqual(r.returncode,0)
        self.assertEqual(r.stdout.splitlines(),["image.png Is this your node","image.png IT MATCHES","image.png CANCEL","image.png SHA-256"])
    def test_each_missing_phrase_fails(self):
        for screen,phrases in [("rpc-settings",["Or your own node by RPC","Read BTC from my node","node.invalid"]),("rpc-cert",["Is this your node","IT MATCHES","CANCEL","SHA-256"])]:
            for phrase in phrases:
                with self.subTest(phrase=phrase):
                    self.assertEqual(self.run_case(screen,phrases,phrase).returncode,1)
    def test_request_keeps_qr_argument(self):
        r=self.run_case("request",["Requested amount"])
        self.assertEqual(r.returncode,0)
        self.assertIn("?chain=xbt&amount=0.00120000",r.stdout)
if __name__=="__main__": unittest.main()
