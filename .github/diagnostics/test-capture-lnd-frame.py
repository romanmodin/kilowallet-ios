import importlib.util,unittest,tempfile
from unittest.mock import Mock
from types import SimpleNamespace
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
 def test_text_phrases_never_enable_qr_mode(self):
  with tempfile.TemporaryDirectory() as d:
   path=Path(d)/"frame.png";path.touch()
   run=Mock(return_value=SimpleNamespace(returncode=0))
   self.assertTrue(m.check_frame(path,run)["passed"])
   self.assertEqual([c.args[0] for c in run.call_args_list],[["build/check-screen",str(path),"Lightning"],["build/check-screen",str(path),"EXPORT SCB"]])
 def test_failed_phrase_and_missing_frame_fail_closed(self):
  with tempfile.TemporaryDirectory() as d:
   path=Path(d)/"frame.png";run=Mock()
   self.assertFalse(m.check_frame(path,run)["passed"]);run.assert_not_called()
   path.touch();run.side_effect=[SimpleNamespace(returncode=1),SimpleNamespace(returncode=0)]
   result=m.check_frame(path,run);self.assertFalse(result["passed"])
   self.assertFalse(result["phrases"]["Lightning"]);self.assertTrue(result["phrases"]["EXPORT SCB"])
 def test_reentry_requires_settings_and_same_process(self):
  fixed=m.prepare_capture_script(Path("scripts/ci-screenshots.sh").read_text())
  self.assertIn('com.apple.Preferences',fixed)
  self.assertIn('test "$original_pid" = "$resumed_pid"',fixed)
  self.assertLess(fixed.index('--wait-for-settings'),fixed.index('55-lnd-reentry-$frame.png'))
  self.assertLess(fixed.index('55-lnd-reentry-$frame.png'),fixed.rindex('xcrun simctl terminate'))
 def test_create_uses_both_specific_phrases(self):
  with tempfile.TemporaryDirectory() as d:
   path=Path(d)/"create.png";path.touch();run=Mock(return_value=SimpleNamespace(returncode=0))
   self.assertTrue(m.check_frame(path,run,phrases=("Your recovery words","I wrote them down"))["passed"])
   self.assertEqual([c.args[0][-1] for c in run.call_args_list],["Your recovery words","I wrote them down"])
 def test_transition_then_ready_is_retained(self):
  with tempfile.TemporaryDirectory() as d:
   root=Path(d);now=[0];shots=[0]
   def pause(n):now[0]+=n
   def run(args,**kwargs):
    if args[0]=="xcrun":shots[0]+=1;Path(args[-1]).touch();return SimpleNamespace(returncode=0)
    return SimpleNamespace(returncode=0 if shots[0]>=2 else 1)
   r=m.wait_for_reentry("fake",root,root/"result.json",run=run,clock=lambda:now[0],pause=pause)
   self.assertTrue(r["ready"]);self.assertEqual(len(r["attempts"]),2);self.assertFalse(r["attempts"][0]["check"]["passed"])
   self.assertTrue((root/"55-lnd-transition-1.png").exists())
 def test_never_ready_fails_with_deadline_receipt(self):
  with tempfile.TemporaryDirectory() as d:
   root=Path(d);now=[0]
   def pause(n):now[0]+=n
   def run(args,**kwargs):
    if args[0]=="xcrun":Path(args[-1]).touch();return SimpleNamespace(returncode=0)
    return SimpleNamespace(returncode=1)
   with self.assertRaises(RuntimeError):m.wait_for_reentry("fake",root,root/"result.json",run=run,clock=lambda:now[0],pause=pause)
   self.assertEqual(now[0],30);self.assertIn('"ready": false',(root/"result.json").read_text())
 def test_on_time_capture_is_not_rejected_for_late_ocr(self):
  with tempfile.TemporaryDirectory() as d:
   root=Path(d);now=[0]
   def pause(n):now[0]+=n
   def run(args,**kwargs):
    if args[0]=="xcrun":Path(args[-1]).touch()
    else:now[0]+=16
    return SimpleNamespace(returncode=0)
   r=m.wait_for_reentry("fake",root,root/"result.json",run=run,clock=lambda:now[0],pause=pause)
   self.assertTrue(r["ready"]);self.assertEqual(r["attempts"][0]["captureElapsedSeconds"],5)
   self.assertGreater(r["elapsedSeconds"],30)
 def test_settings_requires_its_own_screen_text(self):
  with tempfile.TemporaryDirectory() as d:
   root=Path(d);now=[0];seen=[]
   def pause(n):now[0]+=n
   def run(args,**kwargs):
    if args[0]=="xcrun":Path(args[-1]).touch()
    else:seen.append(args[-1])
    return SimpleNamespace(returncode=0)
   r=m.wait_for_reentry("fake",root,root/"r.json",run=run,clock=lambda:now[0],pause=pause,max_wait=60,phrases=("Settings",),prefix="55-settings-transition")
   self.assertEqual(seen,["Settings"]);self.assertEqual(r["attempts"][0]["file"],"55-settings-transition-1.png")
 def test_late_capture_still_rejected(self):
  with tempfile.TemporaryDirectory() as d:
   root=Path(d);now=[0]
   def pause(n):now[0]+=n
   def run(args,**kwargs):
    if args[0]=="xcrun":now[0]+=31;Path(args[-1]).touch()
    return SimpleNamespace(returncode=0)
   with self.assertRaises(RuntimeError):m.wait_for_reentry("fake",root,root/"r.json",run=run,clock=lambda:now[0],pause=pause)
 def test_analysis_timeout_fails_closed(self):
  with tempfile.TemporaryDirectory() as d:
   path=Path(d)/"frame.png";path.touch()
   run=Mock(side_effect=m.subprocess.TimeoutExpired("ocr",60))
   self.assertFalse(m.check_frame(path,run)["passed"])
   self.assertTrue(all(c.kwargs["timeout"]==60 for c in run.call_args_list))
if __name__=="__main__":unittest.main()
