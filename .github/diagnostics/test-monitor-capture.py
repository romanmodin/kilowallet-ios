import importlib.util,tempfile,unittest,time,json
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import Mock
spec=importlib.util.spec_from_file_location("monitor",Path(__file__).with_name("monitor-capture.py"));m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
class TestMonitor(unittest.TestCase):
 def test_capture_exit_is_preserved_and_sampling_stops(self):
  for code in [0,17]:
   with self.subTest(code=code),tempfile.TemporaryDirectory() as d:
    sample=Mock(return_value={"sample":True});path=Path(d)/"out.jsonl"
    def run(cmd):time.sleep(.03);return SimpleNamespace(returncode=code)
    self.assertEqual(m.monitored_capture(["fake"],path,run,sample,.005),code)
    count=sample.call_count;time.sleep(.02);self.assertEqual(sample.call_count,count)
    self.assertTrue(all(json.loads(l)["sample"] for l in path.read_text().splitlines()))
 def test_telemetry_failure_does_not_mask_capture_failure(self):
  with tempfile.TemporaryDirectory() as d:
   def run(cmd):time.sleep(.02);return SimpleNamespace(returncode=23)
   path=Path(d)/"out.jsonl"
   self.assertEqual(m.monitored_capture(["fake"],path,run,Mock(side_effect=RuntimeError("probe failed")),.005),23)
   self.assertIn("probe failed",path.read_text())
 def test_host_commands_are_bounded_and_omit_arguments(self):
  run=Mock(side_effect=m.subprocess.TimeoutExpired("probe",3));r=m.sample_host(run)
  self.assertEqual(len(run.call_args_list),3)
  self.assertTrue(all(c.kwargs["timeout"]==3 for c in run.call_args_list))
  self.assertIn("error",r["ps"]);self.assertNotIn("args",m.COMMANDS[-1][-1])
if __name__=="__main__":unittest.main()
