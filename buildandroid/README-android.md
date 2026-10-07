# Building OpenCPN for Android (reproducible)

This documents the Android build for the `abewartech/OpenCPN` fork.
Two artifacts exist:

| Artifact | What it is | Built by |
|---|---|---|
| `libgorp.so` (arm64/armhf) | Native corelib: chart engine, CM93, S57, GL renderer, JNI bridge | CMake + NDK (this doc, also CI) |
| APK/AAB | Qt5 Java wrapper + `libgorp.so` + assets | Qt 5.15 `androiddeployqt` (manual, see §4) |

## 1. Prerequisites (pinned)

- JDK 17
- CMake ≥ 3.16, Ninja, gettext
- Android SDK command-line tools
- Android NDK **r26.1.10909125** (`ndk;26.1.10909125`)
- Android platform **android-35** (for `android.jar`; the manifest targets API 35,
  minSdk 24)

## 2. One-time SDK setup

```bash
export JAVA_HOME=/path/to/jdk-17
export ANDROID_HOME=$HOME/android-sdk
export PATH=$ANDROID_HOME/cmdline-tools/latest/bin:$PATH

sdkmanager "platform-tools" "platforms;android-35" \
           "build-tools;35.0.0" "ndk;26.1.10909125"
```

## 3. Build the native corelib

```bash
git clone https://github.com/abewartech/OpenCPN.git && cd OpenCPN

TOOL_BASE=$ANDROID_HOME/ndk/26.1.10909125/toolchains/llvm/prebuilt/linux-x86_64

# arm64 (primary)
cmake -S . -B build-android-arm64 -G Ninja \
  -DCMAKE_BUILD_TYPE=Release \
  -DOCPN_TARGET_TUPLE:STRING="Android-arm64;21;arm64" \
  -Dtool_base="$TOOL_BASE" \
  -DOCPN_BUILD_TEST=OFF -DOCPN_BUNDLE_DOCS=OFF
cmake --build build-android-arm64
# -> build-android-arm64/libgorp.so

# armhf (secondary)
cmake -S . -B build-android-armhf -G Ninja \
  -DCMAKE_BUILD_TYPE=Release \
  -DOCPN_TARGET_TUPLE:STRING="Android-armhf;21;armhf" \
  -Dtool_base="$TOOL_BASE" \
  -DOCPN_BUILD_TEST=OFF -DOCPN_BUNDLE_DOCS=OFF
cmake --build build-android-armhf
```

Notes:

- The first configure downloads ~311 MB of prebuilt Qt5/wxWidgets/OpenSSL
  (`OCPNAndroidCoreBuildSupport` v1.2) into `cache/`. Reconfigures reuse it
  (verified in CI: "Reusing cached Android support libs"). Override the location
  with `-DOCPN_ANDROID_CACHEDIR=/path/to/cache`.
- `lunasvg` is fetched from GitHub at configure time (pinned by CMake
  FetchContent to the `master` branch — see `CMakeLists.txt`).
- No undocumented manual steps: the commands above work from a clean checkout.

## 4. Java layer compile check (no Qt needed)

```bash
bash buildandroid/ci-compile-java.sh \
  $ANDROID_HOME/platforms/android-35/android.jar
```

This type-checks every `.java` under `buildandroid/android/src` against
`android.jar` plus minimal Qt stubs (`buildandroid/ci-stubs`). The stubs are
never packaged.

## 5. Host unit tests

```bash
cmake -S android/tests -B build-android-tests
cmake --build build-android-tests
ctest --test-dir build-android-tests --output-on-failure
```

Covers: `getSystemDirs()` contract parsing, SAF tree-URI handling, CM93
dataset directory validation.

## 6. Assembling the APK (manual, Qt 5.15)

The APK wrapper needs Qt 5.15 for Android and the wxQt-for-Android stack from
`OCPNAndroidCoreBuildSupport`; that combination is documented here but not
executed in CI:

1. Build/install Qt 5.15.x for Android (arm64) or reuse the prebuilt Qt in the
   support bundle (`cache/OCPNAndroidCoreBuildSupport/qt5/build_arm64_O3`).
2. Place `libgorp.so` where `androiddeployqt` expects the app lib (renamed to match `android:extractNativeLibs` config if needed).
3. Run `androiddeployqt --input android-libopencpn.so-deployment-settings.json
   --output android-build` from `buildandroid/android`.
4. Sign with your own keystore (`apksigner`).

Manifest notes (`buildandroid/android/AndroidManifest.xml`):

- `targetSdkVersion=35`, `minSdkVersion=24`.
- GPS tracking is a foreground service (`org.opencpn.GPSServer`,
  `foregroundServiceType="location"`); the notification channel/texts live in
  `res/values/strings.xml`.
- The Play License check is **opt-in**: set manifest meta-data
  `org.opencpn.enforce_play_license=true` for commercial Play builds; open
  builds skip it.
- Never commit a real Google Maps API key: the manifest ships the placeholder
  `__GOOGLE_MAPS_API_KEY__`.

## 7. CI

`.github/workflows/android.yml` runs on every push/PR touching Android-related
paths:

- `corelib` (arm64, armhf): full NDK build, uploads `libopencpn.so`.
- `java-check`: the script from §4.
- `unit-tests`: the tests from §5.

A red build fails the job; warnings are not suppressed.
