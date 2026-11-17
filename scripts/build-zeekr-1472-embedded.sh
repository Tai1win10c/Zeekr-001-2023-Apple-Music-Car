#!/usr/bin/env bash
set -euo pipefail

die() { printf '%s\n' "$*" >&2; exit 1; }
if [[ $# -ne 6 ]]; then
    die "Usage: $0 NPATCH_1.1.0_JAR MODULE_APK ORIGINAL_BASE_APK ORIGINAL_ARM64_APK ORIGINAL_XXHDPI_APK NEW_OUTPUT_DIR
Optional environment: JAVA_HOME (JDK 21), SDK_BUILD_TOOLS (directory containing aapt, apksigner and zipalign)."
fi

jar=$1 module=$2 base=$3 arm64=$4 density=$5 output=$6
export JAVA_HOME=${JAVA_HOME:-/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home}
sdk_tools=${SDK_BUILD_TOOLS:-$HOME/Library/Android/sdk/build-tools/37.0.0}
java=$JAVA_HOME/bin/java
aapt=$sdk_tools/aapt
apksigner=$sdk_tools/apksigner
zipalign=$sdk_tools/zipalign
jar_sha=903f5899f710fe920d2a48035f91d0b2a1f86eee86fcff53618ea9f9bd0b6a3d
apple_sha=88ba590ec2e1ea33c4458daf59489faee2cef297a9b4071e18cf82ef531100aa

for tool in "$java" "$aapt" "$apksigner" "$zipalign"; do
    [[ -x "$tool" ]] || die "Missing tool: $tool"
done
java_major=$("$java" -version 2>&1 | sed -n 's/.*version "\([0-9][0-9]*\).*/\1/p')
[[ -n "$java_major" && "$java_major" -ge 21 ]] || die 'NPatch requires JDK 21 or newer; set JAVA_HOME.'
for file in "$jar" "$module" "$base" "$arm64" "$density"; do
    [[ -f "$file" && -r "$file" ]] || die "Missing input: $file"
done
[[ ! -e "$output" && ! -L "$output" ]] || die "Output path already exists: $output"
output_parent=$(dirname -- "$output")
[[ -d "$output_parent" && -w "$output_parent" ]] || die "Output parent is not writable: $output_parent"

sha256() { shasum -a 256 | awk '{print $1}'; }
[[ $(sha256 < "$jar") == "$jar_sha" ]] || die 'NPatch must be the official v1.1.0 build 857 release JAR.'

badging() { "$aapt" dump badging "$1"; }
verify_host() {
    local file=$1 split=${2:-} info match
    info=$(badging "$file") || die "Cannot read APK manifest: $file"
    match="package: name='com.apple.android.music' versionCode='1472' versionName='4.9.7.2'"
    if [[ -n "$split" ]]; then
        match="package: name='com.apple.android.music' versionCode='1472' versionName='' split='$split'"
    fi
    [[ "$info" == "$match" || "$info" == "$match "* || "$info" == "$match"$'\n'* ]] ||
        die "Unexpected package, version or split: $file"
    if [[ "$split" == config.arm64_v8a ]]; then
        [[ "$info" == *"native-code: 'arm64-v8a'"* ]] || die "Missing arm64 native code: $file"
    elif [[ -z "$split" ]]; then
        [[ "$info" == *"launchable-activity: name='com.apple.android.music.onboarding.activities.SplashActivity'"* ]] ||
            die "Missing Apple Music launch activity: $file"
    fi
}

api28_signer() {
    local result digest
    result=$("$apksigner" verify --min-sdk-version 28 --max-sdk-version 28 --print-certs "$1") ||
        die "APK signature does not verify for Android 9: $1"
    digest=$(printf '%s\n' "$result" | awk 'tolower($0) ~ /signer.*certificate sha-256 digest:/ && tolower($0) !~ /source stamp/ {print $NF}')
    [[ "$digest" =~ ^[0-9a-f]{64}$ ]] || die "Could not verify a single API 28 signer: $1"
    printf '%s\n' "$digest"
}

verify_host "$base"
verify_host "$arm64" config.arm64_v8a
verify_host "$density" config.xxhdpi
for file in "$base" "$arm64" "$density"; do
    [[ $(api28_signer "$file") == "$apple_sha" ]] || die "Unexpected original Apple Music signer: $file"
done
module_id=$(badging "$module" | sed -n "s/^package: name='\([^']*\)'.*/\1/p")
[[ "$module_id" == dev.amenhancer.module.debug ]] ||
    die "The 1472 car preview requires an AM++ debug module: $module_id"
unzip -p "$module" host-profiles/4.9.7.2-1472.json | python3 -c '
import json, sys
profile = json.load(sys.stdin)
if profile.get("productionEnabled") is not False or profile.get("zeekr1472DualPane", {}).get("contractVersion") != 1:
    sys.exit(1)
' || die 'The debug module does not contain the 1472 car preview contract.'

work=$(mktemp -d "$output_parent/.zeekr-1472.XXXXXX")
trap 'rm -rf -- "$work"' EXIT
mkdir "$work/input" "$work/patched"
script_dir=$(dirname -- "$0")
python3 "$script_dir/patch-zeekr-1472-host.py" "$base" "$work/base-unsigned.apk"
"$zipalign" -P 4 4 "$work/base-unsigned.apk" "$work/input/base.apk"
cp -- "$arm64" "$work/input/config.arm64_v8a.apk"
cp -- "$density" "$work/input/config.xxhdpi.apk"

# Keep the stock DEX in the outer APK for the Pie class loader. Disable the
# signature compatibility hooks that reenter File.getPath during startup.
"$java" -jar "$jar" --injectdex --sigbypasslv 0 --embed "$module" -o "$work/patched" \
    "$work/input/base.apk" "$work/input/config.arm64_v8a.apk" "$work/input/config.xxhdpi.apk"

patched_base=$work/patched/base-857-npatched.apk
patched_arm64=$work/patched/config.arm64_v8a-857-npatched.apk
patched_density=$work/patched/config.xxhdpi-857-npatched.apk
verify_host "$patched_base"
verify_host "$patched_arm64" config.arm64_v8a
verify_host "$patched_density" config.xxhdpi
[[ $(badging "$patched_base") == *"sdkVersion:'28'"* ]] || die 'Patched base does not declare minSdk 28.'
patched_sha=$(api28_signer "$patched_base")
[[ "$patched_sha" != "$apple_sha" ]] || die 'Patched base unexpectedly retains the Apple signing certificate.'
for file in "$patched_base" "$patched_arm64" "$patched_density"; do
    [[ $(api28_signer "$file") == "$patched_sha" ]] || die "Patched APK signer differs from the base: $file"
    # NPatch nests the original APK. zipalign can warn about its harmless
    # extraction-version fields even when the 4 KiB alignment check passes.
    "$zipalign" -c -P 4 4 "$file" > "$work/zipalign.log" 2>&1 || {
        tail -n 10 "$work/zipalign.log" >&2
        die "APK alignment check failed: $file"
    }
done
python3 - "$work/input/base.apk" "$patched_base" <<'PYTHON'
import re
import sys
from zipfile import ZipFile

with ZipFile(sys.argv[1]) as original, ZipFile(sys.argv[2]) as patched:
    host_dex = [name for name in original.namelist() if re.fullmatch(r"classes[0-9]*\.dex", name)]
    assert host_dex, "No host DEX found"
    for name in host_dex:
        assert name in patched.namelist(), f"Host DEX is missing from the patched APK: {name}"
        assert original.read(name) == patched.read(name), f"Host DEX changed: {name}"
PYTHON
original_sha=$(unzip -p "$patched_base" assets/npatch/origin.apk | sha256) ||
    die 'Could not read the embedded Apple Music base APK.'
[[ "$original_sha" == "$(sha256 < "$work/input/base.apk")" ]] ||
    die 'The embedded original base differs from the staged Apple Music APK.'
unzip -p "$patched_base" assets/npatch/config.json | python3 -c '
import json, sys
config = json.load(sys.stdin)
if (config.get("useManager") is not False or config.get("newPackage") != "com.apple.android.music"
        or config.get("sigBypassLevel") != 0
        or config.get("lspConfig", {}).get("API_CODE") != 102):
    sys.exit(1)
' || die 'Expected a standalone API 102 embedded configuration.'
embedded_sha=$(unzip -p "$patched_base" "assets/npatch/modules/$module_id.apk" | sha256) ||
    die 'Could not read the embedded AM++ APK.'
[[ "$embedded_sha" == "$(sha256 < "$module")" ]] || die 'The embedded AM++ APK differs from the input.'

# mkdir must succeed so a pre-existing output can never be overwritten.
mkdir -- "$output"
mv -- "$patched_base" "$output/base.apk"
mv -- "$patched_arm64" "$output/config.arm64_v8a.apk"
mv -- "$patched_density" "$output/config.xxhdpi.apk"
printf 'Verified three Android 9 APKs with signer SHA-256 %s.\n' "$patched_sha"
printf 'Install as one set (the official Apple-signed app cannot be updated with this set):\n'
printf 'adb install-multiple %q %q %q\n' "$output/base.apk" "$output/config.arm64_v8a.apk" "$output/config.xxhdpi.apk"
