#!/usr/bin/env bash
# Download the published CI artifact as inert data. Do not evaluate anything from it.
set -euo pipefail
dest=$1
repo=repos/Kilombino/kilowallet
artifact=14715376474
run=44924356695
commit=361d663102b66fc362ce82eda5bfcf6a2395a819
outer=10ee001b86bbb44cd13880d71e78d10d38be34b63202eae2aa78c3bc1267b93d
inner=c6070977d3da505b6726fd732f496ce124d35c66995f5743e6369ea52a9b55de
mkdir "$dest"
gh api "$repo/actions/artifacts/$artifact" > "$dest/metadata.json"
jq -e --arg commit "$commit" --arg digest "sha256:$outer" --argjson run "$run" --argjson id "$artifact" \
    '.id==$id and .name=="roblo-client" and (.expired|not) and .digest==$digest and
     .workflow_run.id==$run and .workflow_run.head_sha==$commit' "$dest/metadata.json" >/dev/null
gh api "$repo/actions/artifacts/$artifact/zip" > "$dest/container.zip"
printf '%s  %s\n' "$outer" "$dest/container.zip" | shasum -a 256 -c -
test "$(unzip -Z1 "$dest/container.zip")" = roblo-client-v0.9.11-kw5.zip
unzip -p "$dest/container.zip" roblo-client-v0.9.11-kw5.zip > "$dest/client.zip"
printf '%s  %s\n' "$inner" "$dest/client.zip" | shasum -a 256 -c -
