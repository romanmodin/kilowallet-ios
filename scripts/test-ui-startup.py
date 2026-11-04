"""Exercise the actual runner fragment; use synthetic files and no simulator."""
from pathlib import Path
import subprocess
import tempfile
import unittest

SCRIPT = Path(__file__).with_name("ci-screenshots.sh").read_text()
FRAGMENT = SCRIPT.split("  # BEGIN UI startup wait:", 1)[1].split("  # END UI startup wait", 1)[0]
FRAGMENT = FRAGMENT.split("\n", 1)[1]

class UiStartupTest(unittest.TestCase):
    def run_case(self, immediate=False, delayed=False, stale=False):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "Documents").mkdir()
            if immediate:
                (root / "Documents/kw-ready-ui-history").touch()
            if stale:
                (root / "Documents/kw-ready-ui-welcome").touch()
            shell = """
set -eu
DATA="$1"
screen=history
count=0
capture_failure_diagnostics() { echo "captured:$1"; }
sleep() {
  count=$((count+1))
  if [ "$test_mode" = delayed ] && [ "$count" -eq 3 ]; then
    touch "$DATA/Documents/kw-ready-ui-history"
  fi
}
"""
            # The variable is separate from the mocked sleep function's arguments.
            shell += '\ntest_mode=' + ('delayed' if delayed else 'none') + '\n'
            return subprocess.run(["bash", "-c", shell + FRAGMENT, "test", tmp],
                                  capture_output=True, text=True)
    def test_exact_ui_marker(self):
        r = self.run_case(immediate=True)
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertIn("Compose UI started after 1s", r.stdout)
    def test_delayed_first_frame(self):
        r = self.run_case(delayed=True)
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertIn("Compose UI started after 4s", r.stdout)
    def test_missing_frame_fails_with_diagnostics(self):
        r = self.run_case()
        self.assertNotEqual(r.returncode, 0)
        self.assertIn("captured:ui-startup", r.stdout)
    def test_other_screen_marker_does_not_pass(self):
        r = self.run_case(stale=True)
        self.assertNotEqual(r.returncode, 0)
        self.assertIn("captured:ui-startup", r.stdout)

if __name__ == "__main__":
    unittest.main()
