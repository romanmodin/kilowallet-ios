#!/usr/bin/env bash
# Test only the Debug preview with a synthetic HTML page in a GitHub-hosted simulator.
set -euo pipefail
[[ "${GITHUB_ACTIONS:-}" == true && "${RUNNER_OS:-}" == macOS ]]
app=$1
bundle=$(/usr/libexec/PlistBuddy -c 'Print CFBundleIdentifier' "$app/Info.plist")
udid=$(xcrun simctl list devices booted -j | python3 -c 'import json,sys
ids=[x["udid"] for k,v in json.load(sys.stdin)["devices"].items() if "iOS" in k for x in v if x["state"]=="Booted" and x["name"].startswith("iPhone")]
assert len(ids)==1, ids
print(ids[0])')
docs=$(xcrun simctl get_app_container "$udid" "$bundle" data)/Documents
root="$docs/roblo-preview"
mkdir "$root" # refuse to replace any prior client
cleanup() {
  xcrun simctl terminate "$udid" "$bundle" >/dev/null 2>&1 || true
  rm -f "$root/index.html"
  rmdir "$root"
}
trap cleanup EXIT
cp scripts/fixtures/roblo-webkit.html "$root/index.html"
SIMCTL_CHILD_KW_ROBLO_PREVIEW=1 xcrun simctl launch "$udid" "$bundle"
for attempt in {1..30}; do
  xcrun simctl io "$udid" screenshot screenshots-logs/roblo-probe.png
  if build/check-screen screenshots-logs/roblo-probe.png 'Offline browser verified'; then exit 0; fi
  sleep 2
done
exit 1
