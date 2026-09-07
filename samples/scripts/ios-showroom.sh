#!/usr/bin/env bash
# Builds the sample showroom for the iOS simulator and launches it.
#
# There is no Xcode project here and none is needed: an iOS application is a `UIApplicationMain`, a
# delegate that owns a window and a root view controller, and all three come out of `platform.UIKit`
# (see `ShowroomEntryPoint.kt`). A simulator `.app` is a directory with a Mach-O and an `Info.plist`,
# which is what this script assembles around the executable Kotlin/Native links.
#
# Usage:
#   samples/scripts/ios-showroom.sh
#   VIDDIK_SIMULATOR=my-device samples/scripts/ios-showroom.sh
set -euo pipefail

cd "$(dirname "$0")/.."

BUNDLE_ID="io.github.youndie.viddik.samples"
DEVICE="${VIDDIK_SIMULATOR:-viddik-iphone}"

# An Apple target does not build on the Linux box — the one exception the repository's build rules
# name. This runs on the mac by definition.
../gradlew :fixtures:linkDebugExecutableIosSimulatorArm64 -q

BIN="fixtures/build/bin/iosSimulatorArm64/debugExecutable/ViddikShowroom.kexe"
[ -f "$BIN" ] || { echo "no executable at $BIN"; exit 1; }

APP="$(mktemp -d)/ViddikShowroom.app"
mkdir -p "$APP"
cp "$BIN" "$APP/ViddikShowroom"

cat > "$APP/Info.plist" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>CFBundleExecutable</key><string>ViddikShowroom</string>
  <key>CFBundleIdentifier</key><string>$BUNDLE_ID</string>
  <key>CFBundleName</key><string>viddik</string>
  <key>CFBundlePackageType</key><string>APPL</string>
  <key>CFBundleShortVersionString</key><string>1.0</string>
  <key>CFBundleVersion</key><string>1</string>
  <key>LSRequiresIPhoneOS</key><true/>
  <key>UIDeviceFamily</key><array><integer>1</integer></array>
  <key>MinimumOSVersion</key><string>15.0</string>
  <!-- COMPOSE MULTIPLATFORM REFUSES TO START WITHOUT THIS. Without the key it throws a sanity check
       on the main queue: no window appears, the process stays alive, and the simulator sits on the
       springboard with nothing to show. Xcode's templates carry it, so a hand-assembled bundle is the
       one place it goes missing. -->
  <key>CADisableMinimumFrameDurationOnPhone</key><true/>
  <!-- WITHOUT THIS THE APP IS LETTERBOXED. A bundle with no launch screen declaration tells iOS it
       was built for a legacy screen, and the system runs it in a compatibility canvas: black bands
       above and below, and a window smaller than the display. An EMPTY dictionary is the whole
       declaration — "this app supports whatever screen it is given". -->
  <key>UILaunchScreen</key><dict/>
  <!-- The scene manifest, and it is why this bundle has a status bar at all: without it the app runs
       on the legacy app-delegate lifecycle and SpringBoard composites no status bar for it. No scene
       delegate and no configurations — declaring the manifest is the whole of it. -->
  <key>UIApplicationSceneManifest</key>
  <dict><key>UIApplicationSupportsMultipleScenes</key><false/></dict>
</dict>
</plist>
PLIST

# Created rather than assumed: `simctl` ships device *types*, not devices, and which ones a given
# machine already has is nobody's business but that machine's. A named device also survives Xcode
# updates renaming the stock ones out from under the script.
if ! xcrun simctl list devices | grep -q "^    $DEVICE ("; then
    # The identifier is what `simctl create` takes, and it is the parenthesised half of each line —
    # hence the sed rather than a grep for the name, which would hand over a trailing bracket.
    #
    # Sorted by the model number and NOT taken off the end of the list: `simctl` prints device types
    # in its own order, which puts the iPhone 6s last. Creating that one against a current runtime
    # fails, and the message names the runtime rather than the choice made here.
    TYPE=$(xcrun simctl list devicetypes \
        | sed -nE 's/^iPhone ([0-9]+).*\((com\.apple\.CoreSimulator\.SimDeviceType\.[^)]+)\)$/\1 \2/p' \
        | sort -n -k1,1 \
        | tail -1 \
        | cut -d' ' -f2)
    RUNTIME=$(xcrun simctl list runtimes \
        | sed -nE 's/^iOS .*(com\.apple\.CoreSimulator\.SimRuntime\.iOS-[0-9-]+).*$/\1/p' \
        | tail -1)
    [ -n "$TYPE" ] && [ -n "$RUNTIME" ] || { echo "no iPhone device type or iOS runtime installed"; exit 1; }
    echo "creating simulator $DEVICE ($TYPE on $RUNTIME)"
    xcrun simctl create "$DEVICE" "$TYPE" "$RUNTIME" >/dev/null
fi

xcrun simctl boot "$DEVICE" 2>/dev/null || true
xcrun simctl bootstatus "$DEVICE" -b >/dev/null 2>&1 || true

# NO `simctl uninstall` FIRST. It wipes the app's data container, and a crash report is written to
# disk and only uploaded on the next launch — an uninstall guarantees the report never arrives, while
# the script prints something reassuring.
xcrun simctl install "$DEVICE" "$APP"
xcrun simctl launch --terminate-running-process "$DEVICE" "$BUNDLE_ID"
