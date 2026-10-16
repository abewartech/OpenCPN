#!/bin/bash
# APK sanity check for the OpenCPN Android build.
# Usage: apk-sanity-check.sh <path-to-apk>
# Exits 0 when the APK looks like a valid OpenCPN Android package,
# exits 1 (failing the CI step) otherwise.
#
# Guards against packaging regressions, in particular the class of bug
# where the manifest's launcher activity (or any bridge class) is missing
# from the dex — i.e. the native<->Java bridge silently dead at runtime.
set -u
APK="${1:?usage: apk-sanity-check.sh <apk>}"

fail() { echo "::error::$1"; exit 1; }
pass() { echo "OK: $1"; }

[ -f "$APK" ] || fail "APK not found: $APK"
python3 - "$APK" << 'EOF' || fail "APK sanity check failed"
import sys, zipfile

apk = sys.argv[1]
try:
    z = zipfile.ZipFile(apk)
except zipfile.BadZipFile:
    print("::error::not a valid ZIP: " + apk)
    sys.exit(1)
names = z.namelist()
print(f"OK: valid ZIP, {len(names)} entries")

def need(cond, label):
    print(("OK: " if cond else "::error::MISSING ") + label)
    if not cond:
        sys.exit(1)

need('AndroidManifest.xml' in names, "AndroidManifest.xml present")
need('lib/arm64-v8a/libopencpn_arm64-v8a.so' in names,
     "lib/arm64-v8a/libopencpn_arm64-v8a.so present")
need(any('libQt5Core' in n for n in names), "Qt5Core native lib present")
need(any('libQt5Gui' in n for n in names), "Qt5Gui native lib present")

dexes = [n for n in names if n.endswith('.dex')]
need(len(dexes) > 0, "at least one classes dex present")

# The Java bridge: the manifest launches org.opencpn.OCPNActivity and
# native code calls ~85 bridge methods on it. If the class is missing
# from every dex, every one of those calls fails at runtime.
blob = b''.join(z.read(d) for d in dexes)
need(b'Lorg/opencpn/OCPNActivity;' in blob,
     "bridge class Lorg/opencpn/OCPNActivity; in dex")
need(b'Lorg/opencpn/OCPNSettingsActivity;' in blob,
     "Lorg/opencpn/OCPNSettingsActivity; in dex")
need(b'Lar/com/daidalos/afiledialog/FileChooserActivity;' in blob,
     "SAF FileChooserActivity in dex")
print("ALL APK SANITY CHECKS PASSED")
EOF
pass "apk-sanity-check finished"
