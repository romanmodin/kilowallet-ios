#!/bin/bash
# Boots an iPhone simulator, installs the app and screenshots its main screens.
# Each screen is set up by the app itself in Debug builds with KW_DEMO=<screen> (a throwaway,
# in-memory "abandon … about" test wallet; the Keychain is never touched), and the app writes a
# marker file into its Documents folder when the screen has settled.
set -euo pipefail
APP="$1"; OUT="$2"; ONLY="${3:-}"
mkdir -p build
CHECKER="build/check-screen"
xcrun swiftc scripts/check-screen.swift -o "$CHECKER"
BUNDLE=$(/usr/libexec/PlistBuddy -c "Print CFBundleIdentifier" "$APP/Info.plist")
mkdir -p "$OUT" screenshots-logs

UDID=$(xcrun simctl list devices available -j | python3 -c '
import json, sys
d = json.load(sys.stdin)["devices"]
best = None
for rt, devs in d.items():
    if "iOS" not in rt: continue
    for x in devs:
        if x["name"].startswith("iPhone") and "Pro Max" not in x["name"] and "SE" not in x["name"]:
            best = (rt, x["name"], x["udid"])
print(best[2]); print(best[0], best[1], file=sys.stderr)
')
echo "simulator: $UDID"
xcrun simctl boot "$UDID" || true
xcrun simctl bootstatus "$UDID" -b
xcrun simctl status_bar "$UDID" override --time 9:41 --batteryState charged --batteryLevel 100 --cellularBars 4 --wifiBars 3 || true
xcrun simctl install "$UDID" "$APP"

capture_lnd_log() {
  local log_path="$DATA/Library/Application Support/lnd/demo/logs/bitcoin/mainnet/lnd.log"
  if [ -f "$log_path" ]; then
    cp "$log_path" screenshots-logs/lnd-live.log
    tail -25 screenshots-logs/lnd-live.log
  else
    echo "no LND log at $log_path"
  fi
}

capture_failure_diagnostics() {
  local phase="$1"
  xcrun simctl io "$UDID" screenshot "$OUT/$phase-failed-$name.png" || true
  local pid
  pid=$(awk -F': ' 'NF == 2 { print $2 }' "screenshots-logs/$name.launch" | tail -1)
  if [[ "$pid" =~ ^[0-9]+$ ]]; then
    sample "$pid" 5 -file "screenshots-logs/$name.$phase.sample.txt" || true
  fi
  xcrun simctl spawn "$UDID" log show --last 3m --style compact     --predicate 'process == "Kilowallet"' >"screenshots-logs/$name.$phase.system.log" 2>&1 || true
}

n=0
failures=0
for screen in welcome history create restore watch home simple lightning ln-starting ln-running ln-failed ark locked receive send review replace settings pool-intro pools pool-join pool-create pool-pay pool-round home@es receive@es settings@es lightning@es ln-running@es pools@es pool-round@zh home@zh dice backup cpfp receive-taproot settings-taproot pool-history psbt immature dice@es backup@es pool-history@es psbt@es ark-ready ark-ready@es ark@es ln-bridge ln-move settings-notify ln-bridge@es ark-live tor-live tor-log ln-live wallets private note request export coins recipients filters units backup-check pool-v6 pool-v6@es pool-create-v6; do
  n=$((n + 1))
  base=${screen%@*}
  name=${screen/@/-}
  if [ -n "$ONLY" ] && [ "$screen" != "$ONLY" ]; then continue; fi
  xcrun simctl terminate "$UDID" "$BUNDLE" 2>/dev/null || true
  DATA=$(xcrun simctl get_app_container "$UDID" "$BUNDLE" data)
  mkdir -p "$DATA/Documents"
  rm -f "$DATA/Documents/kw-ready-$base" "$DATA/Documents/kw-demo-selected" "$DATA/Documents/kw-ready-ui-$screen"
  printf %s "$screen" > "$DATA/Documents/kw-demo-case"
  # Let simctl finish launching before polling, without attaching a terminal whose
  # teardown can race the following launch. Keep each app's own stdout/stderr.
  log="$PWD/screenshots-logs/$name.log"
  SIMCTL_CHILD_KW_DEMO="$screen" xcrun simctl launch --terminate-running-process \
    --stdout="$log" --stderr="$PWD/screenshots-logs/$name.stderr" "$UDID" "$BUNDLE" | tee "screenshots-logs/$name.launch"
  # Hosted Intel simulators can be slow before SwiftUI is constructed. Keep this
  # separate from the unchanged deadline for preparing the requested screen.
  selected=""
  for startup in $(seq 1 120); do
    selected=$(cat "$DATA/Documents/kw-demo-selected" 2>/dev/null || true)
    [ "$selected" = "$screen" ] && break
    sleep 1
  done
  if [ "$selected" != "$screen" ]; then
    echo "$screen: startup failed; selected: ${selected:-none}"
    capture_failure_diagnostics startup
    exit 1
  fi
  echo "$screen: startup selected exact case after ${startup}s"
  # BEGIN UI startup wait: selection in Swift alone does not prove Compose rendered.
  # On the first launch of run 41934535236, the exact welcome UI was
  # visible in the diagnostic capture taken after the marker deadline.
  ui_limit=120
  if [ "$screen" = welcome ]; then ui_limit=300; fi
  for ui_startup in $(seq 1 "$ui_limit"); do
    [ -f "$DATA/Documents/kw-ready-ui-$screen" ] && break
    sleep 1
  done
  if [ ! -f "$DATA/Documents/kw-ready-ui-$screen" ]; then
    echo "$screen: Compose UI marker missing after $ui_limit polls"
    capture_failure_diagnostics ui-startup
    exit 1
  fi
  echo "$screen: Compose UI started after ${ui_startup}s"
  # END UI startup wait
  limit=20; { [ "$base" = home ] || [ "$base" = simple ] || [ "$base" = immature ]; } && limit=150
  case "$base" in pool*) limit=90 ;; esac
  # The real Lightning engine: LND starts, makes a throwaway wallet and syncs with the chain's peers.
  # Tor first (up to 3 min), then the node, the watchtower and Kilombino's node by its onion.
  [ "$base" = ln-live ] && limit=2600
  # The real Tor (Arti in the app): it bootstraps, then the Electrum server is reached through it.
  { [ "$base" = tor-live ] || [ "$base" = tor-log ]; } && limit=420
  # The real Ark engine: it starts, makes a throwaway wallet on the Ark server and hands out an address.
  [ "$base" = ark-live ] && limit=300
  for i in $(seq 1 $limit); do
    [ -f "$DATA/Documents/kw-ready-$base" ] && break
    sleep 1
  done
  selected=$(cat "$DATA/Documents/kw-demo-selected" 2>/dev/null || true)
  echo "$screen: simulator selected: ${selected:-none}"
  if [ "$selected" = "$screen" ] && [ -f "$DATA/Documents/kw-ready-$base" ]; then echo "$screen: ready after ${i}s"; else
    echo "$screen: NOT ready after ${limit}s"
    if [ "$base" = ln-live ]; then capture_lnd_log; fi
    capture_failure_diagnostics readiness
    exit 1
  fi
  sleep 3
  SHOT="$OUT/$(printf %02d $n)-$name.png"
  xcrun simctl io "$UDID" screenshot "$SHOT"
  expected=""
  case "$screen" in
    welcome) expected="Restore from words" ;;
    pool-intro|pools|pool-round|pools@es|pool-round@zh|pool-v6|pool-v6@es) expected="Kilojoin" ;;
    pool-join) expected="Join" ;;
    pool-create|pool-create-v6) expected="Open a pool" ;;
    pool-pay) expected="Pay through a pool" ;;
    receive@es) expected="Recibir" ;;
    settings@es) expected="Ajustes" ;;
    replace) expected="Cancel" ;;
    recipients) expected="Saved recipients" ;;
    backup-check) expected="Check written backup" ;;
    filters) expected="Search and filters" ;;
    units) expected="0.00250000" ;;
    coins) expected="Choose exact coins" ;;
    review) expected="Review" ;;
    wallets) expected="Wallets" ;;
    history) expected="Mining payout" ;;
    simple) expected="advanced" ;;
    ln-running|ln-running@es) expected="460 550" ;;
    restore) expected="recovery words" ;;
    tor-live) expected="Settings" ;;
    private) expected="Balance hidden" ;;
    note) expected="October test payout" ;;
    request) expected="Requested amount" ;;
    export) expected="Export history?" ;;
  esac
  if [ -n "$expected" ]; then
    check_args=("$SHOT" "$expected")
    [ "$screen" = request ] && check_args+=("?chain=xbt&amount=0.00120000")
    rendered=false
    for attempt in 1 2 3; do
      if "$CHECKER" "${check_args[@]}"; then rendered=true; break; fi
      sleep 3
      xcrun simctl io "$UDID" screenshot "$SHOT"
    done
    if [ "$rendered" != true ]; then
      echo "$screen: rendered screen did not match readiness marker"
      capture_failure_diagnostics rendered
      failures=$((failures + 1))
    else
      echo "$screen: visible title verified"
    fi
  fi
  if [ "$base" = ln-live ]; then
    capture_lnd_log
    ls -la "$DATA/Library/Application Support/lnd" || true
  fi
  grep "KW " "screenshots-logs/$name.log" 2>/dev/null || true
  case "$base" in tor-live|tor-log|ln-live)
    TLOG="$DATA/Library/Application Support/tor.log"
    [ -f "$TLOG" ] && cp "$TLOG" "screenshots-logs/tor-$name.log" && tail -15 "screenshots-logs/tor-$name.log" || echo "no Tor log at $TLOG"
    grep "KW " "screenshots-logs/$name.log" || true ;;
  esac
  if [ "$base" = ark-live ]; then
    ALOG="$DATA/Library/Application Support/ark/debug.log"
    [ -f "$ALOG" ] && cp "$ALOG" screenshots-logs/ark-live.log && grep -viE 'mnemonic|seed|passphrase' screenshots-logs/ark-live.log | tail -25 || echo "no Ark log at $ALOG"
    ls -la "$DATA/Library/Application Support/ark" || true
  fi
done
xcrun simctl terminate "$UDID" "$BUNDLE" 2>/dev/null || true
ls -la "$OUT"

# Preserve all screen evidence even if one OCR assertion fails.
[ "$failures" -eq 0 ]
