import subprocess,unittest
from pathlib import Path
S=Path(__file__).with_name('ci-screenshots.sh').read_text()
F=S[S.index('  expected=""'):S.index('    rendered=false')]
class RestoreCaptureTest(unittest.TestCase):
 def args(self, screen):
  r=subprocess.run(['bash','-c','set -eu; SHOT=fixture.png; screen="$1"; '+F+'printf "%s\\n" "${check_args[@]}"; fi','test',screen],text=True,capture_output=True,check=True)
  return r.stdout.splitlines()
 def test_restore_requires_actual_form(self):
  self.assertEqual(self.args('restore'),['fixture.png','I also used a passphrase'])
 def test_welcome_cannot_satisfy_restore(self):
  words='Kilowallet Hot wallet Its recovery words are generated on this device. Restore from words Watch wallet'
  self.assertFalse(all(x.lower() in words.lower() for x in self.args('restore')[1:]))
 def test_restore_fixture_satisfies_required_words(self):
  words='Restore from words recovery words 0 words I also used a passphrase RESTORE'
  self.assertTrue(all(x.lower() in words.lower() for x in self.args('restore')[1:]))
 def test_request_retains_uri_check(self):
  self.assertEqual(self.args('request'),['fixture.png','Requested amount','?chain=xbt&amount=0.00120000'])
if __name__=='__main__': unittest.main()
