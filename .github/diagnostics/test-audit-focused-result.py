import importlib.util,copy,unittest,json
from pathlib import Path
spec=importlib.util.spec_from_file_location("audit",Path(__file__).with_name("audit-focused-result.py"));m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
def good():
 return {"source":m.SOURCE,"captureExit":0,"checks":{k:True for k in ["lightningUsesTor","onionPeerConnected","watchtowerRegistered","emptyChannelBackupRpc","nativeQualificationCompleted"]},"passed":True,"createScreen":{"captureExit":0,"exists":True,"passed":True,"phrases":{"Your recovery words":True,"I wrote them down":True}},"frameChecks":{f:{"exists":True,"passed":True,"phrases":{"Lightning":True,"EXPORT SCB":True}} for f in m.FRAMES},"settingsReadiness":{"ready":True,"limitSeconds":60,"analysisTimeoutPerPhraseSeconds":60,"elapsedSeconds":10,"attempts":[{"captureExit":0,"elapsedSeconds":10,"captureElapsedSeconds":5,"check":{"passed":True,"phrases":{"Settings":True,"General":True,"Accessibility":True}}}]},"reentryReadiness":{"ready":True,"limitSeconds":30,"analysisTimeoutPerPhraseSeconds":60,"elapsedSeconds":10,"attempts":[{"captureExit":0,"elapsedSeconds":10,"captureElapsedSeconds":5,"check":{"passed":True,"phrases":{"Lightning":True,"EXPORT SCB":True}}}]},"reentryPidReceipt":"original=123\nresumed=123\n"}
class T(unittest.TestCase):
 def test_settings_icon_alone_cannot_pass(self):
  v=good();v["settingsReadiness"]["attempts"][-1]["check"]["phrases"]={"Settings":True}
  with self.assertRaises(AssertionError):m.validate(v)

 def test_complete_evidence_still_requires_visual_review(self):
  r=m.validate(good());self.assertTrue(r["automatedEvidencePassed"]);self.assertFalse(r["releaseApproved"])
 def test_wrong_source(self):
  v=good();v["source"]="0"*40
  with self.assertRaises(AssertionError):m.validate(v)
 def test_missing_each_frame(self):
  for f in m.FRAMES:
   v=good();del v["frameChecks"][f]
   with self.assertRaises(AssertionError):m.validate(v)
 def test_springboard_create(self):
  v=good();v["createScreen"]["phrases"]["Your recovery words"]=False
  with self.assertRaises(AssertionError):m.validate(v)
 def test_restart_is_not_same_process_reentry(self):
  for pid in ["original=123\nresumed=124\n","original=\nresumed=\n",None]:
   v=good();v["reentryPidReceipt"]=pid
   with self.assertRaises((AssertionError,TypeError)):m.validate(v)
 def test_native_failure(self):
  v=good();v["checks"]["watchtowerRegistered"]=False
  with self.assertRaises(AssertionError):m.validate(v)
 def test_failed_capture(self):
  v=good();v["captureExit"]=1
  with self.assertRaises(AssertionError):m.validate(v)
 def test_readiness_timeout_rejected(self):
  v=good();v["reentryReadiness"]["attempts"][-1]["captureElapsedSeconds"]=31
  with self.assertRaises(AssertionError):m.validate(v)
 def test_readiness_without_screen_rejected(self):
  v=good();v["reentryReadiness"]["attempts"][-1]["check"]["phrases"]["Lightning"]=False
  with self.assertRaises(AssertionError):m.validate(v)
 def test_unready_rejected(self):
  v=good();v["reentryReadiness"]["ready"]=False
  with self.assertRaises(AssertionError):m.validate(v)
 def test_unverified_settings_rejected(self):
  v=good();v["settingsReadiness"]["attempts"][-1]["check"]["phrases"]["Settings"]=False
  with self.assertRaises(AssertionError):m.validate(v)
 def test_slow_analysis_after_on_time_capture_is_valid(self):
  v=good();v["reentryReadiness"]["elapsedSeconds"]=90;v["reentryReadiness"]["attempts"][-1]["elapsedSeconds"]=90
  self.assertTrue(m.validate(v)["automatedEvidencePassed"])
 def test_settings_late_capture_rejected(self):
  v=good();v["settingsReadiness"]["attempts"][-1]["captureElapsedSeconds"]=61
  with self.assertRaises(AssertionError):m.validate(v)
 def test_actual_rc5_attempt2_is_rejected(self):
  v=json.loads((Path(__file__).parent/"fixtures/rc5-focused-attempt2.json").read_text())
  with self.assertRaises(AssertionError):m.validate(v)
 def test_actual_rc5_native_failures_independently_rejected(self):
  actual=json.loads((Path(__file__).parent/"fixtures/rc5-focused-attempt2.json").read_text())
  for key,value in actual["checks"].items():
   if value is False:
    v=good();v["checks"][key]=False
    with self.assertRaises(AssertionError):m.validate(v)
 def test_actual_rc5_frames_rejected_even_with_native_success(self):
  actual=json.loads((Path(__file__).parent/"fixtures/rc5-focused-attempt2.json").read_text())
  v=good();v["frameChecks"]=actual["frameChecks"]
  with self.assertRaises(AssertionError):m.validate(v)
 def test_actual_rc5_readiness_rejected_even_with_native_success(self):
  actual=json.loads((Path(__file__).parent/"fixtures/rc5-focused-attempt2.json").read_text())
  v=good();v["reentryReadiness"]=actual["reentryReadiness"];v["reentryPidReceipt"]=actual["reentryPidReceipt"]
  with self.assertRaises(AssertionError):m.validate(v)
if __name__=="__main__":unittest.main()
