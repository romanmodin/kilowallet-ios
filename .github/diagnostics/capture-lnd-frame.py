"""Synthetic-only diagnostics. Never updates the app, its engines, or wallets."""
import hashlib,importlib.util,json,re,shutil,subprocess,sys,time
from pathlib import Path
SOURCE="5ac15b5eeb26296d69008a39e7ff1b4e23d4f8c5"
SCRIPT_SHA="168c35fafecefca744d2416fee8b07f8d1917d78a61eb7b0f1b31507b087d6c3"
ANCHOR='done\nxcrun simctl terminate "$UDID" "$BUNDLE" 2>/dev/null || true\nls -la "$OUT"'
EXTRA='''done
# Diagnostic-only: capture while the same synthetic app remains foregrounded.
for frame in 1 2 3; do
  sleep 5
  xcrun simctl io "$UDID" screenshot "$OUT/55-lnd-followup-$frame.png"
done
# Switch to Settings, then resume the same app process: actual foreground re-entry.
original_pid=$(awk -F': ' 'NF == 2 { print $2 }' screenshots-logs/ln-live.launch | tail -1)
xcrun simctl launch "$UDID" com.apple.Preferences > screenshots-logs/background.launch
sleep 5
xcrun simctl io "$UDID" screenshot "$OUT/55-background-settings.png"
build/check-screen "$OUT/55-background-settings.png" Settings
xcrun simctl launch "$UDID" "$BUNDLE" | tee screenshots-logs/reentry.launch
resumed_pid=$(awk -F': ' 'NF == 2 { print $2 }' screenshots-logs/reentry.launch | tail -1)
printf 'original=%s\nresumed=%s\n' "$original_pid" "$resumed_pid" > screenshots-logs/reentry-pids.txt
test -n "$original_pid"
test "$original_pid" = "$resumed_pid"
# Retain transition frames; require the real screen within a bounded wait.
python3 -u candidate-frame-harness/.github/diagnostics/capture-lnd-frame.py --wait-for-reentry "$UDID" "$OUT"
for frame in 1 2 3; do
  sleep 5
  xcrun simctl io "$UDID" screenshot "$OUT/55-lnd-reentry-$frame.png"
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
def check_frame(path, run=subprocess.run, phrases=("Lightning", "EXPORT SCB")):
    # check-screen's optional fourth argument is a QR suffix, NOT another phrase.
    # Validate each phrase with a separate text-only invocation.
    if not path.exists():
        return {"exists": False, "phrases": {}, "passed": False}
    phrases={phrase: run(["build/check-screen",str(path),phrase]).returncode==0
             for phrase in phrases}
    return {"exists": True, "phrases": phrases, "passed": all(phrases.values())}

def wait_for_reentry(udid,out,receipt,max_wait=30,run=subprocess.run,clock=time.monotonic,pause=time.sleep):
    start=clock();attempts=[];ready=False
    while clock()-start < max_wait:
        pause(min(5,max_wait-(clock()-start)))
        path=out/f"55-lnd-transition-{len(attempts)+1}.png"
        capture=run(["xcrun","simctl","io",udid,"screenshot",str(path)])
        check=check_frame(path,run) if capture.returncode==0 else {"passed":False}
        elapsed=clock()-start
        attempts.append({"file":path.name,"elapsedSeconds":elapsed,"captureExit":capture.returncode,"check":check})
        ready=check["passed"] and elapsed<=max_wait
        if ready:break
    result={"ready":ready,"limitSeconds":max_wait,"elapsedSeconds":clock()-start,"attempts":attempts}
    receipt.write_text(json.dumps(result,indent=2))
    if not ready:raise RuntimeError("Lightning screen did not become ready within the re-entry deadline")
    return result

def main():
    assert subprocess.check_output(["git","rev-parse","HEAD"],text=True).strip()==SOURCE
    original=Path("scripts/ci-screenshots.sh").read_text()
    # Independent synthetic create-screen recapture; retain failure and still collect LND.
    created=subprocess.run(["bash","scripts/ci-screenshots.sh",sys.argv[1],"screenshots","create"])
    create_check=check_frame(Path("screenshots/03-create.png"),phrases=("Your recovery words", "I wrote them down"))
    create_check["captureExit"]=created.returncode
    Path("screenshots-logs/create-focused-result.json").write_text(json.dumps(create_check,indent=2))
    if not create_check["passed"]:
        subprocess.run(["xcrun","simctl","spawn","booted","log","show","--last","5m","--style","compact","--predicate",'process == "Kilowallet"'],stdout=Path("screenshots-logs/create-system.log").open("w"),stderr=subprocess.STDOUT)
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
    frames=["55-ln-live.png"]+[f"55-lnd-followup-{n}.png" for n in [1,2,3]]+[f"55-lnd-reentry-{n}.png" for n in [1,2,3]]
    frame_results={frame:check_frame(Path("screenshots")/frame) for frame in frames}
    report["frameChecks"]=frame_results
    readiness=Path("screenshots-logs/reentry-readiness.json")
    report["reentryReadiness"]=json.loads(readiness.read_text()) if readiness.exists() else None
    report["createScreen"]=create_check
    report["reentryPidReceipt"]=Path("screenshots-logs/reentry-pids.txt").read_text() if Path("screenshots-logs/reentry-pids.txt").exists() else None
    Path("screenshots-logs/lnd-focused-result.json").write_text(json.dumps(report,indent=2))
    frame_ok=all(item["passed"] for item in frame_results.values())
    assert result.returncode==0 and report["passed"] and frame_ok and created.returncode==0 and create_check["passed"], "Focused LND runtime or app-frame evidence failed; inspect retained diagnostics"
if __name__=="__main__":
    if sys.argv[1:2]==["--wait-for-reentry"]:
        wait_for_reentry(sys.argv[2],Path(sys.argv[3]),Path("screenshots-logs/reentry-readiness.json"))
    else:main()
