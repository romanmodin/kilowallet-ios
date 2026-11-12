#!/bin/bash
# Binds LND for iOS as lnd-engine/ENGINE says (Lightning Fork 21.2 since 2 Nov 2026). Needs macOS
# with Xcode and the pinned Go.
set -euo pipefail
cd "$(dirname "$0")/.."
ENGINE=$PWD/lnd-engine/ENGINE
pin() { grep "^$1=" "$ENGINE" | cut -d= -f2-; }
out=$PWD/build/lnd; rm -rf "$out"; mkdir -p "$out"
src=$out/src
git clone --quiet --filter=blob:none "$(pin repo)" "$src"
git -C "$src" checkout --quiet "$(pin commit)"
test "$(git -C "$src" rev-parse HEAD)" = "$(pin commit)"
ls "$src"/mobile/*_generated.go >/dev/null

# Diagnostic candidate only. Never change the app ENGINE artifact pins here.
root=$PWD
candidate=$root/.github/diagnostics/recovery-retry
btcwallet=$out/btcwallet
wallet_commit=f2c79d772865e29c955516666c5a57dd12994633
patch_sha=fb08082fd64a16a1cc0e6b2313f3d61811a3d1d01b62cb71f378f9a308396864
test "$(shasum -a 256 "$candidate/candidate.patch" | cut -d ' ' -f1)" = "$patch_sha"
git clone --quiet --filter=blob:none https://github.com/btcsuite/btcwallet "$btcwallet"
git -C "$btcwallet" checkout --quiet "$wallet_commit"
test "$(git -C "$btcwallet" rev-parse HEAD)" = "$wallet_commit"
git -C "$btcwallet" apply --check "$candidate/candidate.patch"
git -C "$btcwallet" apply "$candidate/candidate.patch"
cp "$candidate/wallet_retry_test.go" "$btcwallet/wallet/kilowallet_retry_test.go"
cp "$candidate/wallet_sync_retry_test.go" "$btcwallet/wallet/kilowallet_sync_retry_test.go"
cp "$candidate/waddrmgr_retry_test.go" "$btcwallet/waddrmgr/kilowallet_retry_test.go"
export GOTOOLCHAIN=local
(cd "$src" && go mod edit -replace="github.com/btcsuite/btcwallet=$btcwallet" &&
 go test -mod=mod github.com/btcsuite/btcwallet/wallet github.com/btcsuite/btcwallet/waddrmgr \
 -run 'TestKilowallet|Test.*Birthday|Test.*Sync' -count=1 -v -timeout=120s) 2>&1 | tee "$out/candidate-tests.log"
export GOFLAGS=-mod=mod GOTOOLCHAIN=local PATH="$(go env GOPATH)/bin:$PATH"
go version
go install "golang.org/x/mobile/cmd/gomobile@$(pin gomobile)"
go install "golang.org/x/mobile/cmd/gobind@$(pin gomobile)"
gomobile init
tags=$(pin tags); iosversion=$(pin iosversion)
test -n "$tags" && test -n "$iosversion"
cd "$src"
time gomobile bind -target=ios,iossimulator -iosversion "$iosversion" -tags="$tags" \
  -ldflags "-s -w" -v -o "$out/Lndmobile.xcframework" ./mobile
cd "$out"
find Lndmobile.xcframework -maxdepth 2 | sort
du -sh Lndmobile.xcframework Lndmobile.xcframework/*
# A stable zip (sorted entries, fixed times) so the hash depends on the contents only.
find Lndmobile.xcframework -exec touch -h -t 202601010000 {} +
find Lndmobile.xcframework \( -type f -o -type l \) | LC_ALL=C sort | zip -q -X -y -9 Lndmobile.xcframework.zip -@
shasum -a 256 Lndmobile.xcframework.zip | tee Lndmobile.xcframework.zip.sha256
ls -la Lndmobile.xcframework.zip

# Receipt binds the candidate source, full module graph and produced archive.
(cd "$src" && go list -mod=mod -m all) > "$out/resolved-modules.txt"
git -C "$src" diff -- go.mod go.sum > "$out/engine-module.diff"
git -C "$btcwallet" diff -- wallet/wallet.go > "$out/btcwallet-source.diff"
python3 "$root/.github/diagnostics/recovery-retry/write-native-receipt.py" "$root" "$out"
