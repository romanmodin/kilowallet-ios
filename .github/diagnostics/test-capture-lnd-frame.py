import importlib.util,unittest
from pathlib import Path
ROOT=Path(__file__).parent
spec=importlib.util.spec_from_file_location("capture",ROOT/"capture-lnd-frame.py");m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
spec=importlib.util.spec_from_file_location("runtime",Path("scripts/check-runtime.py"));runtime=importlib.util.module_from_spec(spec);spec.loader.exec_module(runtime)
class CaptureTest(unittest.TestCase):
 def test_real_pass(self):self.assertTrue(m.check_lnd((ROOT/"lnd-positive.log").read_text(),runtime)["passed"])
 def test_actual_startup_failure_rejected(self):self.assertFalse(m.check_lnd((ROOT/"lnd-42163150260-failed.log").read_text(),runtime)["passed"])
 def test_each_missing_marker_rejected(self):
  lines=(ROOT/"lnd-positive.log").read_text().splitlines()
  for i in range(len(lines)):
   with self.subTest(i=i):self.assertFalse(m.check_lnd("\n".join(lines[:i]+lines[i+1:]),runtime)["passed"])
 def test_late_false_rejected(self):self.assertFalse(m.check_lnd((ROOT/"lnd-positive.log").read_text()+"KW ln-live: native qualification completed: false\n",runtime)["passed"])
 def test_frames_insert_before_original_cleanup(self):
  source=Path("scripts/ci-screenshots.sh").read_text();fixed=m.prepare_capture_script(source)
  self.assertIn('55-lnd-followup-$frame.png',fixed)
  self.assertEqual(fixed.replace(m.EXTRA,m.ANCHOR),source)
  self.assertLess(fixed.index('55-lnd-followup-$frame.png'),fixed.rindex('xcrun simctl terminate'))
  self.assertIn('limit=2600',fixed)
 def test_changed_source_rejected(self):
  with self.assertRaises(AssertionError):m.prepare_capture_script(Path("scripts/ci-screenshots.sh").read_text()+"\n")
if __name__=="__main__":unittest.main()
