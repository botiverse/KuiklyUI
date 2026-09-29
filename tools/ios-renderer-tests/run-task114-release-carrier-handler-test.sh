#!/usr/bin/env bash
set -euo pipefail

if [[ "$#" -ne 3 ]]; then
  echo "usage: $0 <carrier-root> <carrier-derived-data> <evidence-dir>" >&2
  exit 64
fi

trusted_root="$(cd "$(dirname "$0")/../.." && pwd)"
carrier_root="$(cd "$1" && pwd)"
derived_data="$(cd "$2" && pwd)"
evidence_dir="$3"
mkdir -p "$evidence_dir"
evidence_dir="$(cd "$evidence_dir" && pwd)"

renderer_archive="$derived_data/Build/Products/Debug-iphonesimulator/OpenKuiklyIOSRender/libOpenKuiklyIOSRender.a"
test -f "$renderer_archive"

app_dir="$evidence_dir/Task114ReleaseCarrier.app"
rm -rf "$app_dir"
mkdir -p "$app_dir"
cp "$trusted_root/tools/ios-renderer-tests/Task114ReleaseCarrierInfo.plist" \
  "$app_dir/Info.plist"

case "$(uname -m)" in
  arm64) simulator_arch="arm64" ;;
  x86_64) simulator_arch="x86_64" ;;
  *)
    echo "unsupported macOS runner architecture: $(uname -m)" >&2
    exit 1
    ;;
esac

xcrun --sdk iphonesimulator clang++ \
  -target "${simulator_arch}-apple-ios17.0-simulator" \
  -fobjc-arc \
  -fblocks \
  -Werror \
  -ObjC \
  -I "$carrier_root/core-render-ios/include" \
  -I "$carrier_root/core-render-ios/MacSupport" \
  "$trusted_root/tools/ios-renderer-tests/Task114ReleaseCarrierHandlerTest.m" \
  "$renderer_archive" \
  -framework UIKit \
  -framework QuartzCore \
  -framework CoreGraphics \
  -framework Foundation \
  -framework CoreText \
  -framework ImageIO \
  -framework AVFoundation \
  -framework CoreMedia \
  -framework CoreVideo \
  -lc++ \
  -o "$app_dir/Task114ReleaseCarrier"

nm "$app_dir/Task114ReleaseCarrier" |
  tee "$evidence_dir/linked-symbols.txt" |
  grep -F "OBJC_CLASS_\$_KRRichTextView"

runtime_id="$(
  xcrun simctl list runtimes --json |
    /usr/bin/python3 -c '
import json, sys
runtimes = [
    item for item in json.load(sys.stdin)["runtimes"]
    if item.get("isAvailable")
    and item.get("identifier", "").startswith("com.apple.CoreSimulator.SimRuntime.iOS-")
]
if not runtimes:
    raise SystemExit("no available iOS Simulator runtime")
print(runtimes[-1]["identifier"])
'
)"

device_type_id="$(
  xcrun simctl list devicetypes --json |
    /usr/bin/python3 -c '
import json, sys
devices = json.load(sys.stdin)["devicetypes"]
preferred = next((item for item in devices if item["name"] == "iPhone 16"), None)
if preferred is None:
    preferred = next((item for item in devices if item["name"].startswith("iPhone")), None)
if preferred is None:
    raise SystemExit("no iPhone Simulator device type")
print(preferred["identifier"])
'
)"

udid="$(xcrun simctl create "Task114Carrier-${GITHUB_RUN_ID:-local}" "$device_type_id" "$runtime_id")"
cleanup() {
  xcrun simctl shutdown "$udid" >/dev/null 2>&1 || true
  xcrun simctl delete "$udid" >/dev/null 2>&1 || true
}
trap cleanup EXIT

xcrun simctl boot "$udid"
xcrun simctl bootstatus "$udid" -b
xcrun simctl install "$udid" "$app_dir"
xcrun simctl launch --terminate-running-process \
  "$udid" build.raft.task114.releasecarrier |
  tee "$evidence_dir/launch.log"

data_container="$(
  xcrun simctl get_app_container "$udid" build.raft.task114.releasecarrier data
)"
receipt="$data_container/tmp/task114-carrier-pass"
for _ in $(seq 1 30); do
  if [[ -f "$receipt" ]]; then
    break
  fi
  sleep 1
done

test -f "$receipt"
test "$(<"$receipt")" = "optional-view-method-safe"
cp "$receipt" "$evidence_dir/receipt.txt"
xcrun simctl spawn "$udid" launchctl print \
  system/com.apple.CoreSimulator.CoreSimulatorService >/dev/null 2>&1 || true
printf 'task114_release_carrier_handler_fixture=PASS\n' |
  tee "$evidence_dir/result.txt"
