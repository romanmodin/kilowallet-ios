#!/usr/bin/env bash
# Exact app view, independent simulator host. This does not qualify a wallet build for release.
set -euo pipefail
test "$GITHUB_ACTIONS" = true && test "$RUNNER_OS" = macOS
src=scripts/roblo-simulator
archive=$1
out=$2
mkdir "$out"
tmp=$(mktemp -d)
udid=""
cleanup() {
  if [[ -n "$udid" ]]; then xcrun simctl shutdown "$udid" >/dev/null 2>&1 || :; xcrun simctl delete "$udid" || :; fi
  rm -rf "$tmp"
}
trap cleanup EXIT
app="$tmp/Preview.app"
mkdir "$app"
bundle=dev.kilowallet.tests.RobloPreview
sdk=$(xcrun --sdk iphonesimulator --show-sdk-path)
xcrun swiftc -parse-as-library -swift-version 5 -D ROBLO_BOOK_TESTS \
  iosApp/iosApp/RobloPublicBook.swift "$src/BookChecks.swift" -o "$tmp/check-book"
"$tmp/check-book" "$src/book-fixture.json" "$tmp/expected-book.js"
xcrun swiftc -parse-as-library -swift-version 5 -D DEBUG -sdk "$sdk" -target "$(uname -m)-apple-ios17.0-simulator" \
  iosApp/iosApp/RobloOfflinePreview.swift iosApp/iosApp/RobloPublicBook.swift "$src/ProbeApp.swift" "$src/ProbeBook.swift" -o "$app/Preview"
cp "$src/book-fixture.json" "$src/book-access.js" "$src/book-search.js" "$app/"
python3 - "$app/Info.plist" "$bundle" <<'PY'
import plistlib, sys
with open(sys.argv[1], 'wb') as f:
    plistlib.dump(dict(CFBundleIdentifier=sys.argv[2], CFBundleExecutable='Preview', CFBundleName='Preview',
                       CFBundleDisplayName='Offline Preview', CFBundleDevelopmentRegion='en', CFBundleInfoDictionaryVersion='6.0',
                       CFBundlePackageType='APPL', CFBundleShortVersionString='1.0', CFBundleVersion='1',
                       LSRequiresIPhoneOS=True, MinimumOSVersion='17.0', UIDeviceFamily=[1],
                       CFBundleSupportedPlatforms=['iPhoneSimulator'], UILaunchScreen={},
                       UIApplicationSceneManifest={'UIApplicationSupportsMultipleScenes':False}), f)
PY
xcrun swiftc scripts/check-screen.swift -o "$tmp/check-screen"
xcrun simctl list -j devices available > "$tmp/devices.json"
read -r device runtime < <(python3 - "$tmp/devices.json" <<'PY'
import json, re, sys
rows=[]
for runtime, devices in json.load(open(sys.argv[1]))['devices'].items():
    m=re.fullmatch(r'com\.apple\.CoreSimulator\.SimRuntime\.iOS-(\d+(?:-\d+)*)',runtime)
    if not m or int(m[1].split('-')[0]) < 17: continue
    for d in devices:
        if d['isAvailable'] and d['name'].startswith('iPhone') and d.get('deviceTypeIdentifier'):
            rows.append((tuple(map(int,m[1].split('-'))), d['deviceTypeIdentifier'], runtime))
assert rows, 'no usable iPhone simulator'
_, device, runtime = max(rows); print(device,runtime)
PY
)
udid=$(xcrun simctl create "Roblo preview $GITHUB_RUN_ID" "$device" "$runtime")
xcrun simctl boot "$udid"
xcrun simctl bootstatus "$udid" -b
xcrun simctl install "$udid" "$app"
container=$(xcrun simctl get_app_container "$udid" "$bundle" data)
mkdir -p "$container/Documents"
python3 scripts/stage-roblo-preview.py "$archive" "$container/Documents"
xcrun simctl launch "$udid" "$bundle"
passed=0
for n in {1..45}; do
  xcrun simctl io "$udid" screenshot "$out/landing.png"
  if "$tmp/check-screen" "$out/landing.png" "Start" && "$tmp/check-screen" "$out/landing.png" "Recovery"; then passed=1; break; fi
  sleep 2
done
if [[ "$passed" != 1 ]]; then
  echo 'Pinned client did not render both Start and Recovery.' >&2
  exit 1
fi
# Second launch is the only one that installs the synthetic API fixture. No signing or wallet.
xcrun simctl terminate "$udid" "$bundle"
result="$container/tmp/kw-offers-result.json"
test ! -e "$result"
xcrun simctl launch "$udid" "$bundle" --offline-book-probe
for n in {1..100}; do [[ ! -s "$result" ]] || break; sleep 2; done
test -s "$result"
cp "$result" "$out/book-result.json"
xcrun simctl io "$udid" screenshot "$out/offers.png"
python3 "$src/check-book-result.py" "$out/book-result.json"
"$tmp/check-screen" "$out/offers.png" "EUR"
"$tmp/check-screen" "$out/offers.png" "96"
python3 - "$archive" "$out" "$runtime" "$device" <<'PY'
import hashlib,json,os,sys
from pathlib import Path
archive,out,runtime,device=sys.argv[1:]; out=Path(out)
sha=lambda p:hashlib.sha256(Path(p).read_bytes()).hexdigest()
receipt={'run':os.environ['GITHUB_RUN_ID'],'attempt':os.environ['GITHUB_RUN_ATTEMPT'],'source':os.environ['GITHUB_SHA'],
         'runtime':runtime,'deviceType':device,'viewSha256':sha('iosApp/iosApp/RobloOfflinePreview.swift'),
         'clientSha256':sha(archive),'screenshotSha256':sha(out/'landing.png'),'visible':['Start','Recovery'],
         'orderBook':{'fixtureSha256':sha('scripts/roblo-simulator/book-fixture.json'),
                      'nativeAdapterSha256':sha('iosApp/iosApp/RobloPublicBook.swift'),
                      'accessProbeSha256':sha('scripts/roblo-simulator/book-access.js'),
                      'driverSha256':sha('scripts/roblo-simulator/book-search.js'),
                      'resultSha256':sha(out/'book-result.json'),'screenshotSha256':sha(out/'offers.png')},
         'scope':'isolated simulator preview; no wallet engines linked'}
(out/'receipt.json').write_text(json.dumps(receipt,indent=2)+'\n')
PY
