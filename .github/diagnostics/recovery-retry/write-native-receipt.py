import datetime, hashlib, json, os, pathlib, subprocess, sys
root, out = map(pathlib.Path, sys.argv[1:])
def sha(path): return hashlib.sha256(path.read_bytes()).hexdigest()
def head(path): return subprocess.check_output(["git", "-C", str(path), "rev-parse", "HEAD"], text=True).strip()
files = ["Lndmobile.xcframework.zip", "candidate-tests.log", "resolved-modules.txt", "engine-module.diff", "btcwallet-source.diff"]
receipt = {
 "observedAt": datetime.datetime.now(datetime.timezone.utc).isoformat(),
 "publisherRepository": "romanmodin/kilowallet-ios", "publisherCommit": head(root),
 "engineSourceCommit": head(out/"src"), "btcwalletSourceCommit": head(out/"btcwallet"),
 "patchSha256": sha(root/".github/diagnostics/recovery-retry/candidate.patch"),
 "goVersion": subprocess.check_output(["go", "version"], text=True).strip(),
 "runId": os.environ.get("GITHUB_RUN_ID"), "runAttempt": os.environ.get("GITHUB_RUN_ATTEMPT"),
 "candidateTestsPassed": True, "unsignedEngineOnly": True,
 "approvedForSigning": False, "appNativeQualificationPassed": False, "renderingQualified": False,
 "sha256": {name: sha(out/name) for name in files},
}
assert receipt["engineSourceCommit"] == "cae303b36594a2c48c9ce0cbb238672e3a1b284e"
assert receipt["btcwalletSourceCommit"] == "f2c79d772865e29c955516666c5a57dd12994633"
assert receipt["patchSha256"] == "fb08082fd64a16a1cc0e6b2313f3d61811a3d1d01b62cb71f378f9a308396864"
(out/"candidate-native-receipt.json").write_text(json.dumps(receipt, indent=2)+"\n")
