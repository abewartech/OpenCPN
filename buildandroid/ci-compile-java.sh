#!/bin/bash
# ci-compile-java.sh — compile-check the Android Java layer.
#
# Usage: ci-compile-java.sh <path-to-android.jar>
#
# Compiles every .java under buildandroid/android/src plus the Qt stubs in
# buildandroid/ci-stubs against android.jar. The app R class
# (org.opencpn.opencpn.R) is auto-generated from the symbols the sources
# reference. This catches syntax and type errors in CI without needing the
# full Qt-for-Android toolchain. The stubs are never packaged into an APK.
set -euo pipefail

ANDROID_JAR="${1:?usage: ci-compile-java.sh <android.jar>}"
HERE="$(cd "$(dirname "$0")" && pwd)"
SRC="$HERE/android/src"
STUBS="$HERE/ci-stubs"
OUT="$(mktemp -d)"

# Java sources: app + vendored aFileDialog chooser library.
# The Play Licensing client (com.google.android.vending) is excluded: it
# depends on Apache HttpClient (removed from the SDK in API 23) and is only
# linked for opt-in commercial Play builds; stubs in ci-stubs cover the API
# surface QtActivity references.
find "$SRC" -name '*.java' \
  | grep -v '/src/com/google/android/vending/' > "$OUT/sources.txt"
find "$HERE/android/library/afilechooser/src/main/java" -name '*.java' \
  >> "$OUT/sources.txt"
find "$STUBS" -name '*.java' >> "$OUT/sources.txt"

# Vendored binary deps (support-v4, play-services) go on the classpath.
CP="$ANDROID_JAR"
for jar in "$HERE/android/libs/"*.jar; do
  [ -f "$jar" ] && CP="$CP:$jar"
done

# Auto-generate R classes from referenced symbols:
#   org.opencpn.opencpn.R  (app, from android/src)
#   ar.com.daidalos.afiledialog.R  (chooser library)
mkdir -p "$OUT/r/org/opencpn/opencpn" "$OUT/r/ar/com/daidalos/afiledialog"
python3 - "$SRC" "$HERE/android/library/afilechooser/src/main/java" \
        "$OUT/r/org/opencpn/opencpn/R.java" \
        "$OUT/r/ar/com/daidalos/afiledialog/R.java" <<'EOF'
import os, re, sys
app_src, lib_src, app_out, lib_out = sys.argv[1:5]
pat = re.compile(r'\bR\.(string|drawable|layout|array|xml|id|menu|color|style)\.([A-Za-z0-9_]+)')

def collect(root):
    refs = {}
    for dirpath, _, files in os.walk(root):
        for f in files:
            if f.endswith('.java'):
                text = open(os.path.join(dirpath, f), encoding='utf-8', errors='ignore').read()
                for typ, name in pat.findall(text):
                    refs.setdefault(typ, set()).add(name)
    return refs

def emit(refs, package, out):
    with open(out, 'w') as fh:
        fh.write(f'package {package};\n')
        fh.write('// Auto-generated compile-check stub. Real R is generated at APK build time.\n')
        fh.write('public final class R {\n')
        i = 1
        for typ in sorted(refs):
            fh.write(f'    public static final class {typ} {{\n')
            for name in sorted(refs[typ]):
                fh.write(f'        public static final int {name} = {i};\n')
                i += 1
            fh.write('    }\n')
        fh.write('}\n')
    return sum(len(v) for v in refs.values())

n1 = emit(collect(app_src), 'org.opencpn.opencpn', app_out)
n2 = emit(collect(lib_src), 'ar.com.daidalos.afiledialog', lib_out)
print(f'generated R: app={n1} symbols, lib={n2} symbols')
EOF
echo "$OUT/r/org/opencpn/opencpn/R.java" >> "$OUT/sources.txt"
echo "$OUT/r/ar/com/daidalos/afiledialog/R.java" >> "$OUT/sources.txt"
wc -l "$OUT/sources.txt"

javac -version
if javac -nowarn -encoding UTF-8 \
  -cp "$CP" \
  -d "$OUT/classes" \
  @"$OUT/sources.txt" > "$OUT/javac.log" 2>&1; then
  echo "JAVA COMPILE CHECK PASSED"
else
  echo "JAVA COMPILE CHECK FAILED"
  grep -v "^Note:" "$OUT/javac.log" | head -60
  exit 1
fi
