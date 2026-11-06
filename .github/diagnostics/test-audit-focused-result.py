import importlib.util,copy,unittest
from pathlib import Path
spec=importlib.util.spec_from_file_location("audit",Path(__file__).with_name("audit-focused-result.py"));m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
def good():
 return {"source":m.SOURCE,"captureExit":0,"checks":{k:True for k in ["lightningUsesTor","onionPeerConnected","watchtowerRegistered","emptyChannelBackupRpc","nativeQualificationCompleted"]},"passed":True,"createScreen":{"captureExit":0,"exists":True,"passed":True,"phrases":{"Your recovery words":True,"I wrote them down":True}},"frameChecks":{f:{"exists":True,"passed":True,"phrases":{"Lightning":True,"EXPORT SCB":True}} for f in m.FRAMES},"reentryPidReceipt":"original=123\nresumed=123\n"}
class T(unittest.TestCase):
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
if __name__=="__main__":unittest.main()
