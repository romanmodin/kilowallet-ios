"""Synthetic-only diagnostics. Never updates the app, its engines, or wallets."""
import hashlib,importlib.util,json,re,shutil,subprocess,sys,time
from pathlib import Path
SOURCE="68148c8c5741c7b84b37f33322e67273f7208a19"
SCRIPT_SHA="8dbdab79978c7fd685964067fd650353cc097d64d071b4fb0c81dad8b4669709"
ANCHOR='done\nls -la "$OUT"'
EXTRA='''done
# Diagnostic-only: capture while the same synthetic app remains foregrounded.
for frame in 1 2 3; do
  sleep 5
  xcrun simctl io "$UDID" screenshot "$OUT/55-lnd-followup-$frame.png"
done
# Switch to Settings, then resume the same app process: actual foreground re-entry.
original_pid=$(awk -F': ' 'NF == 2 { print $2 }' screenshots-logs/ln-live.launch | tail -1)
xcrun simctl launch "$UDID" com.apple.Preferences > screenshots-logs/background.launch
python3 -u candidate-frame-harness/.github/diagnostics/capture-lnd-frame.py --wait-for-settings "$UDID" "$OUT"
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
    results={}
    for phrase in phrases:
        try:
            results[phrase]=run(["build/check-screen",str(path),phrase],timeout=60).returncode==0
        except subprocess.TimeoutExpired:
            results[phrase]=False
    phrases=results
    return {"exists": True, "phrases": phrases, "passed": all(phrases.values())}

def wait_for_reentry(udid,out,receipt,max_wait=30,run=subprocess.run,clock=time.monotonic,pause=time.sleep,phrases=("Lightning","EXPORT SCB"),prefix="55-lnd-transition"):
    start=clock();attempts=[];ready=False
    while clock()-start < max_wait:
        pause(min(5,max_wait-(clock()-start)))
        path=out/f"{prefix}-{len(attempts)+1}.png"
        try:
            capture=run(["xcrun","simctl","io",udid,"screenshot",str(path)],timeout=max(0.001,max_wait-(clock()-start)))
        except subprocess.TimeoutExpired:
            capture=type("TimedOut",(),{"returncode":124})()
        captured=clock()-start
        check=check_frame(path,run,phrases=phrases) if capture.returncode==0 else {"passed":False}
        elapsed=clock()-start
        attempts.append({"file":path.name,"elapsedSeconds":elapsed,"captureElapsedSeconds":captured,"captureExit":capture.returncode,"check":check})
        ready=check["passed"] and captured<=max_wait
        if ready:break
    result={"ready":ready,"limitSeconds":max_wait,"analysisTimeoutPerPhraseSeconds":60,"elapsedSeconds":clock()-start,"attempts":attempts}
    receipt.write_text(json.dumps(result,indent=2))
    if not ready:raise RuntimeError("Required screen did not become ready within the deadline")
    return result

def preflight_settings(udid,out,logs,run=subprocess.run,wait=wait_for_reentry):
    # Prove the simulator's background app renders before the expensive LND run.
    # Final Settings/background/PID/re-entry assertions remain independent.
    logs.mkdir(parents=True,exist_ok=True)
    with (logs/"settings-preflight.launch").open("w") as launch:
        run(["xcrun","simctl","launch",udid,"com.apple.Preferences"],stdout=launch,stderr=subprocess.STDOUT,check=True,timeout=60)
    try:
        return wait(udid,out,logs/"settings-preflight.json",max_wait=60,phrases=("Settings","General","Accessibility"),prefix="00-settings-preflight")
    except Exception:
        with (logs/"settings-preflight-system.log").open("w") as log:
            try:
                run(["xcrun","simctl","spawn",udid,"log","show","--last","3m","--style","compact","--predicate",'process == "Preferences" OR process == "SpringBoard"'],stdout=log,stderr=subprocess.STDOUT,timeout=30)
            except Exception as diagnostic_error:
                log.write(str(diagnostic_error))
        raise

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
    fixtures=Path("candidate-frame-harness/.github/diagnostics/fixtures")
    fixture_results={name:check_frame(fixtures/name,phrases=("Settings","General","Accessibility")) for name in ["settings-icon-springboard.png","settings-content-positive.png"]}
    Path("screenshots-logs/settings-fixture-results.json").write_text(json.dumps(fixture_results,indent=2))
    assert not fixture_results["settings-icon-springboard.png"]["passed"], "SpringBoard must not qualify as Settings"
    assert fixture_results["settings-content-positive.png"]["passed"], "Actual Settings fixture must be recognized"
    boot=json.loads(subprocess.check_output(["xcrun","simctl","list","devices","available","-j"]))
    ids=[d["udid"] for devices in boot["devices"].values() for d in devices if d["state"]=="Booted"]
    assert len(ids)==1
    preflight_settings(ids[0],Path("screenshots"),Path("screenshots-logs"))
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
    settings=Path("screenshots-logs/settings-readiness.json")
    report["settingsReadiness"]=json.loads(settings.read_text()) if settings.exists() else None
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
    elif sys.argv[1:2]==["--wait-for-settings"]:
        out=Path(sys.argv[3])
        result=wait_for_reentry(sys.argv[2],out,Path("screenshots-logs/settings-readiness.json"),max_wait=60,phrases=("Settings","General","Accessibility"),prefix="55-settings-transition")
        shutil.copyfile(out/result["attempts"][-1]["file"],out/"55-background-settings.png")
    else:main()
