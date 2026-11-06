"""Synthetic-only diagnostics. Never updates the app, its engines, or wallets."""
import hashlib,importlib.util,json,re,shutil,subprocess,sys
from pathlib import Path
SOURCE="e9aae6502f3a8a1aa7703424540d5a568214fa5e"
SCRIPT_SHA="168c35fafecefca744d2416fee8b07f8d1917d78a61eb7b0f1b31507b087d6c3"
ANCHOR='done\nxcrun simctl terminate "$UDID" "$BUNDLE" 2>/dev/null || true\nls -la "$OUT"'
EXTRA='''done
# Diagnostic-only: capture while the same synthetic app remains foregrounded.
for frame in 1 2 3; do
  sleep 5
  xcrun simctl io "$UDID" screenshot "$OUT/55-lnd-followup-$frame.png"
done
xcrun simctl terminate "$UDID" "$BUNDLE" 2>/dev/null || true
ls -la "$OUT"'''
def prepare_capture_script(source):
    assert hashlib.sha256(source.encode()).hexdigest()==SCRIPT_SHA, "Unexpected screenshot source"
    assert source.count(ANCHOR)==1
    return source.replace(ANCHOR,EXTRA)
def check_lnd(log,runtime):
    checks=runtime.check_engines(log)["checks"]
    selected={key:checks[key] for key in ["lightningUsesTor","onionPeerConnected","watchtowerRegistered","emptyChannelBackupRpc"]}
    completed=re.findall(r"KW ln-live: native qualification completed: (true|false)",log)
    selected["nativeQualificationCompleted"]=bool(completed and completed[-1]=="true")
    return {"checks":selected,"passed":all(selected.values()),"physicalPhoneTested":False,"fundedChannelRestoreTested":False,"visualApprovalRequiresManualReview":True}
def main():
    assert subprocess.check_output(["git","rev-parse","HEAD"],text=True).strip()==SOURCE
    original=Path("scripts/ci-screenshots.sh").read_text()
    patched=prepare_capture_script(original)
    Path("build/capture-lnd-frame.sh").write_text(patched)
    result=subprocess.run(["bash","build/capture-lnd-frame.sh",sys.argv[1],"screenshots","ln-live"])
    # Retain only public chain/filter caches, never wallet.db, channel.db or keys.
    boot=json.loads(subprocess.check_output(["xcrun","simctl","list","devices","available","-j"]))
    ids=[d["udid"] for devices in boot["devices"].values() for d in devices if d["state"]=="Booted"]
    assert len(ids)==1
    bundle=subprocess.check_output(["/usr/libexec/PlistBuddy","-c","Print CFBundleIdentifier",sys.argv[1]+"/Info.plist"],text=True).strip()
    data=Path(subprocess.check_output(["xcrun","simctl","get_app_container",ids[0],bundle,"data"],text=True).strip())
    cache=data/"Library/Application Support/lnd/demo"
    entries=[]
    for f in cache.rglob("*"):
        if f.is_file() and f.name in {"neutrino.db","block_headers.bin","reg_filter_headers.bin"}:
            target=Path("screenshots-logs/chain-cache")/f.relative_to(cache)
            target.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(f,target)
            entries.append({"path":str(target),"size":target.stat().st_size,"sha256":hashlib.sha256(target.read_bytes()).hexdigest()})
    spec=importlib.util.spec_from_file_location("runtime","scripts/check-runtime.py");runtime=importlib.util.module_from_spec(spec);spec.loader.exec_module(runtime)
    log=Path("screenshots-logs/ln-live.log").read_text(errors="replace")
    report=check_lnd(log,runtime)
    report.update({"source":SOURCE,"screenshotSourceSha256":SCRIPT_SHA,"captureExit":result.returncode,"chainCaches":entries})
    Path("screenshots-logs/lnd-focused-result.json").write_text(json.dumps(report,indent=2))
    # OCR rejects SpringBoard followups. Actual glyph completeness still needs visual review.
    frames=["55-ln-live.png"]+[f"55-lnd-followup-{n}.png" for n in [1,2,3]]
    frame_ok=True
    for frame in frames:
        path=Path("screenshots")/frame
        if not path.exists():frame_ok=False;continue
        if subprocess.run(["build/check-screen",str(path),"Lightning","EXPORT SCB"]).returncode:frame_ok=False
    assert result.returncode==0 and report["passed"] and frame_ok, "Focused LND runtime or app-frame evidence failed; inspect retained diagnostics"
if __name__=="__main__":main()
