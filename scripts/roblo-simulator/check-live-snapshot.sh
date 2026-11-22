#!/bin/bash
# Explicit read-only probe in a throwaway hosted simulator; never run on a phone.
# Requires a booted simulator with the matching Debug app already installed.
set -eu
[[ "${GITHUB_ACTIONS:-}" == true && "${RUNNER_OS:-}" == macOS ]]
udid=$1
app=$2
out=$3
bundle=$(/usr/libexec/PlistBuddy -c 'Print CFBundleIdentifier' "$app/Info.plist")
installed=$(xcrun simctl get_app_container "$udid" "$bundle" app)
cmp "$app/Kilowallet" "$installed/Kilowallet"
mkdir "$out"
root=$(xcrun simctl get_app_container "$udid" "$bundle" data)/Documents/roblo-preview
mkdir "$root"
cleanup() {
  xcrun simctl terminate "$udid" "$bundle" >/dev/null 2>&1 || true
  rm -f "$root/index.html"
  rmdir "$root"
}
trap cleanup EXIT
cat > "$root/index.html" <<'PAGE'
<meta name="viewport" content="width=device-width"><h1 id="result">Reading public offers</h1>
<script>
(async () => {
 const response = await fetch("http://kiloblo3ibbey3wridofauizwb467y5osfaycxoecgsf66mggsrw6ryd.onion/api/book/");
 const rows = await response.json();
 if (response.ok && Array.isArray(rows)) document.getElementById("result").textContent = "Verified live snapshot: " + rows.length;
})().catch(() => {});
</script>
PAGE
SIMCTL_CHILD_KW_ROBLO_PREVIEW=1 SIMCTL_CHILD_KW_ROBLO_LIVE_BOOK=1 SIMCTL_CHILD_KW_ROBLO_PROBE=1 xcrun simctl launch --terminate-running-process "$udid" "$bundle"
for attempt in {1..105}; do
  xcrun simctl io "$udid" screenshot "$out/snapshot.png" >/dev/null
  if build/check-screen "$out/snapshot.png" 'Verified live snapshot'; then exit 0; fi
  if build/check-screen "$out/snapshot.png" 'Kilobot unavailable'; then exit 1; fi
  sleep 2
done
exit 1
