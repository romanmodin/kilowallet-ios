#!/usr/bin/env bash
# Compile only. This script has no signing, installation, upload, or Apple account step.
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"
out="$PWD/build/kilobot-device-qa"
mkdir -p "$out"
python3 scripts/roblo-simulator/device-wallet-plist.py prepare "$out/Info.plist"
xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp -configuration Release \
  -sdk iphoneos -destination 'generic/platform=iOS' -derivedDataPath "$out/dd" \
  PRODUCT_BUNDLE_IDENTIFIER=dev.kilowallet.tests.KilobotBook \
  SWIFT_ACTIVE_COMPILATION_CONDITIONS=KW_KILOBOT_DEVICE_QA \
  INFOPLIST_FILE="$out/Info.plist" IPHONEOS_DEPLOYMENT_TARGET=17.0 \
  CODE_SIGNING_ALLOWED=NO CODE_SIGNING_REQUIRED=NO build > build/kilobot-device-build.log 2>&1
app="$out/dd/Build/Products/Release-iphoneos/Kilowallet.app"
python3 scripts/roblo-simulator/device-wallet-plist.py verify "$app/Info.plist"
test ! -e "$app/embedded.mobileprovision"
test ! -e "$app/_CodeSignature"
xcrun lipo "$app/Kilowallet" -verify_arch arm64
xcrun vtool -show-build "$app/Kilowallet" | grep -Eq '^ *platform IOS *$'
ditto -c -k --keepParent "$app" build/kilobot-device-unsigned.zip
python3 - "$app" <<'PY'
import hashlib,json,os,subprocess,sys,zipfile
from pathlib import Path
app=Path(sys.argv[1])
def sha(p): return hashlib.sha256(p.read_bytes()).hexdigest()
def git(ref): return subprocess.check_output(["git","rev-parse",ref],text=True).strip()
archive=Path("build/kilobot-device-unsigned.zip")
with zipfile.ZipFile(archive) as z:
    assert z.testzip() is None
    for name in ["Info.plist","Kilowallet"]:
        assert hashlib.sha256(z.read("Kilowallet.app/"+name)).hexdigest()==sha(app/name)
event=json.loads(Path(os.environ["GITHUB_EVENT_PATH"]).read_bytes())
res=dict(bundle="dev.kilowallet.tests.KilobotBook", platform="iPhoneOS",arch="arm64",
         candidate=event["pull_request"]["head"]["sha"],checkout=git("HEAD"),tree=git("HEAD^{tree}"),
         run=os.environ["GITHUB_RUN_ID"],attempt=os.environ["GITHUB_RUN_ATTEMPT"],
         archiveSHA256=sha(archive), binarySHA256=sha(app/"Kilowallet"), plistSHA256=sha(app/"Info.plist"),
         scope="Unsigned compile of the shared wallet offers panel; no device execution.")
Path("build/kilobot-device-receipt.json").write_text(json.dumps(res,indent=2)+"\n")
PY
