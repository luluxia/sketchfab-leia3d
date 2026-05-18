# sketchfab-leia3d

Sketchfab embed viewer SBS/Leia3D research prototype.

This repository preserves the current working baseline for:

- loading a Sketchfab embed in Cardboard/WebVR stereo mode;
- injecting runtime JavaScript at document start;
- forcing Orbit-style navigation where possible;
- disabling gyro/device-orientation input;
- disabling VR teleport/cursor behavior through Sketchfab webpack/runtime patches;
- disabling Cardboard lens distortion with the verified `vr_ar=1` path and bundle patches.

Current userscript baseline:

```text
2026-05-18-safe-webpack-require
```

Known state:

- Teleport/cursor suppression works in the successful runtime patch path.
- The `safeWebpackRequire` guard avoids the more destructive webpack timing failures seen in later experiments.
- Half-SBS aspect correction is not solved yet. Later attempts involving WebGL projection uniforms, virtual viewport width, CullVisitor projection hooks, and webpack call capture were intentionally not kept in this baseline.

## Layout

```text
userscript/
  sketchfab-sbs-inject.user.js

android-webview/
  Minimal Android WebView APK project that loads Sketchfab fullscreen and injects the script.

docs/
  sketchfab-sbs-vr-analysis.md
  device screenshots from the WebView test run
```

## Build APK

From `android-webview/`:

```powershell
.\gradlew.bat assembleDebug
```

The debug APK will be generated under:

```text
android-webview/app/build/outputs/apk/debug/app-debug.apk
```
