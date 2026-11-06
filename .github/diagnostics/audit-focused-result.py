import json,re,hashlib,sys
from pathlib import Path
SOURCE="5ac15b5eeb26296d69008a39e7ff1b4e23d4f8c5"
FRAMES=["55-ln-live.png"]+[f"55-lnd-followup-{n}.png" for n in range(1,4)]+[f"55-lnd-reentry-{n}.png" for n in range(1,4)]
def validate(v):
 assert v["source"]==SOURCE
 assert v["captureExit"]==0
 assert set(v["checks"])=={"lightningUsesTor","onionPeerConnected","watchtowerRegistered","emptyChannelBackupRpc","nativeQualificationCompleted"}
 assert all(x is True for x in v["checks"].values()) and v["passed"] is True
 c=v["createScreen"];assert c["captureExit"]==0 and c["exists"] is True and c["passed"] is True
 assert c["phrases"]=={"Your recovery words":True,"I wrote them down":True}
 assert set(v["frameChecks"])==set(FRAMES)
 for f in FRAMES:
  x=v["frameChecks"][f];assert x["exists"] is True and x["passed"] is True and x["phrases"]=={"Lightning":True,"EXPORT SCB":True}
 settings=v["settingsReadiness"]
 assert settings["ready"] is True and settings["limitSeconds"]==60 and settings["analysisTimeoutPerPhraseSeconds"]==60
 assert settings["attempts"][-1]["check"]["phrases"]=={"Settings":True}
 assert settings["attempts"][-1]["captureExit"]==0 and settings["attempts"][-1]["check"]["passed"] is True
 assert 0<settings["attempts"][-1]["captureElapsedSeconds"]<=60
 assert settings["attempts"][-1]["captureElapsedSeconds"]<=settings["attempts"][-1]["elapsedSeconds"]<=settings["elapsedSeconds"]
 ready=v["reentryReadiness"]
 assert ready["ready"] is True and ready["limitSeconds"]==30 and ready["analysisTimeoutPerPhraseSeconds"]==60
 assert ready["attempts"]
 last=ready["attempts"][-1]
 assert last["captureExit"]==0 and last["check"]["passed"] is True
 assert last["check"]["phrases"]=={"Lightning":True,"EXPORT SCB":True}
 assert 0<last["captureElapsedSeconds"]<=30
 assert last["captureElapsedSeconds"]<=last["elapsedSeconds"]<=ready["elapsedSeconds"]
 m=re.fullmatch(r"original=([1-9][0-9]*)\nresumed=([1-9][0-9]*)\n",v["reentryPidReceipt"]);assert m and m[1]==m[2]
 return {"automatedEvidencePassed":True,"manualVisualApproval":False,"releaseApproved":False,"note":"Inspect all seven LND frames and Create and Settings pixels before approval; no physical phone or funded recovery qualification."}
def main():
 root=Path(sys.argv[1]);r=json.loads((root/"receipt.json").read_text());assert r["run"]==int(sys.argv[2]) and r["orchestration"]==sys.argv[3] and r["attempt"]==1 and r["status"]=="completed" and r["conclusion"]=="success"
 assert r["artifact"]["apiDigestVerified"] and r["artifact"]["zipCrcVerified"]
 assert r["compilePassed"] and r["capturePassed"]
 v=json.loads((root/"extracted/screenshots-logs/lnd-focused-result.json").read_text());result=validate(v)
 result["imageHashes"]={}
 for f in ["03-create.png","55-background-settings.png",*FRAMES]:
  path=root/"extracted/screenshots"/f;assert path.is_file();result["imageHashes"][f]=hashlib.sha256(path.read_bytes()).hexdigest()
 (root/"automated-qualification.json").write_text(json.dumps(result,indent=2));print(json.dumps(result,indent=2))
if __name__=="__main__":main()
