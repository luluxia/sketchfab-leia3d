# Sketchfab Embed Stereo / SBS Runtime Analysis

Target URL:

`https://sketchfab.com/models/b2359160a4f54e76b5ae427a55d9594d/embed?autostart=1&cardboard=1&internal=1&tracking=0&ui_ar=0&ui_infos=0&ui_snapshots=1&ui_stop=0&ui_theatre=1&ui_watermark=0`

## Executive Summary

Sketchfab does already contain a reusable stereo path. The embed viewer is based on osgjs / osgViewer-derived code, not three.js. Cardboard mode uses Sketchfab's `webVR` feature to set a shared VR cull configuration, then the render leaf draws the same scene twice with left/right projection and model-view matrices into side-by-side viewports.

The useful pipeline exists, but it is coupled to VR mode:

- `cardboard=1` enables `webVR`.
- `webVR` enables stereo rendering through `vrConfig.doVR`.
- `webVR` also forces the camera feature into first-person manipulator mode.
- Cardboard/non-native HMD mode enables a post-process distortion pass.

Important verified finding: adding `vr_ar=1` keeps SBS stereo but disables the Cardboard lens distortion. It also changes some VR background/floor behavior, because the same flag is intended for transparent AR-style VR rendering.

Best current path:

1. Use Sketchfab's internal stereo renderer.
2. Disable distortion with either `vr_ar=1` or by patching `postProcess.doDistortionVR = false`.
3. After VR startup, switch Settings -> Navigation from `First Person` back to `Orbit`.
4. Disable device-orientation event registration.

## Runtime Probe Results

Observed in the direct embed page:

- Main WebGL canvas: `canvas.canvas[data-modeluid="b2359160a4f54e76b5ae427a55d9594d"]`
- Canvas CSS size: about `1281 x 720`
- Canvas backing size in Cardboard path: `3736 x 1986`
- `window.osg`, `window.osgViewer`, `window.THREE`, `window.Sketchfab`, and obvious `viewer/camera/renderer` globals are not exposed.
- The viewer loads hashed webpack bundles from `https://static.sketchfab.com/static/builds/web/dist/`.

The Codex in-app browser allowed read-only probes but rejected write-style bookmarklet injection. Because of that, WebGL monkey-patching could not be executed in that browser. Static bundle analysis and URL-flag visual verification were completed.

A second probe used local Chrome through the Chrome DevTools Protocol with a temporary profile. That path does allow real JS injection.

During one verification pass, direct browser CDP navigation became unstable because the Sketchfab embed endpoint intermittently returned `504 Gateway Time-out`. After the site recovered, Chrome CDP became stable again and was used for the latest validation.

## Key Internal Objects And Modules

The relevant bundles downloaded during this run:

- `.codex-sketchfab-probe/03097b3b7092b581e4a09196735c38a8-v2.js`
- `.codex-sketchfab-probe/1c76918338bfe04b43c19e45141a8782-v2.js`
- `.codex-sketchfab-probe/7af00f6d710174ddc1eacb2911ea4ffa-v2.js`
- `.codex-sketchfab-probe/70e8ed83729a09e2fd0d0b159aaa5300-v2.js`
- `.codex-sketchfab-probe/d723d800d69662aec6e17b82aeec4232-v2.js`

Important discovered symbols:

- `scene.manipulators.orbit.webvr`
- `scene.manipulators.orbit.deviceorientation`
- `scene.manipulators.fps.webvr`
- `scene.manipulators.fps.deviceorientation`
- `webVR` feature/model
- `vr_stereo` URL option
- `vr_ar` URL option
- `doDistortionVR` post-process model flag
- `VR_DISTORTION` shader define
- `uDistortion`, `uProjectionLeft`, `uUnprojectionLeft`

The current bundle patch points are all in webpack module `U6YP` inside `1c76918338bfe04b43c19e45141a8782-v2.js`. Offline validation found 10 replacement hits and successfully rebuilt the patched factory with `eval(...)`, so the current prototype is syntactically valid against this bundle.

## Render Pipeline

### Stereo cull state

Bundle `1c769...` defines an osgSketchfab cull visitor VR config:

```js
vrConfig = {
  doVR: false,
  framebufferWidth: 0,
  framebufferHeight: 0,
  leftRenderWidth: 0,
  rightRenderWidth: 0,
  leftRenderHeight: 0,
  rightRenderHeight: 0,
  rightProjection: mat4,
  leftProjection: mat4,
  leftOffsetView: mat4,
  rightOffsetView: mat4
}
```

When culling under the post-process output node, render leaves receive:

- `leaf.isVR`
- `leaf.leftProjection`
- `leaf.rightProjection`
- `leaf.leftModelView`
- `leaf.rightModelView`
- `leaf.leftView`
- `leaf.rightView`

### SBS draw

Bundle `7af00...` overrides render-leaf drawing. If `leaf.isVR` is true, it draws the same geometry twice:

```js
viewport(0, 0, leftRenderWidth, leftRenderHeight)
projection = leftProjection
modelView = leftModelView
view = leftView
draw()

viewport(leftRenderWidth, 0, leftRenderWidth, rightRenderHeight)
projection = rightProjection
modelView = rightModelView
view = rightView
draw()
```

So this is real two-pass stereo via viewport split and per-eye matrices, not just a shader UV offset.

### Cardboard fake HMD

For non-native Cardboard, `getConfigCardboard()` creates a fake HMD:

- `displayName: "fake-cardboard"`
- `isPresenting: false`
- `capabilities.hasPosition = false`
- `capabilities.hasRotation = false`
- `getEyeParameters("left"|"right")`
- left/right offsets from inter-lens distance
- left/right projection matrices from VR field-of-view

This is the part worth reusing for naked-eye SBS.

## Distortion Pipeline

The post-process model computes:

```js
useDistortionVR = webVR && doDistortionVR && vrStereo
```

The webVR feature currently does:

```js
doDistortionVR = !hasNativeHMD && !vrAr
```

Then `passThrough.glsl` conditionally applies:

```glsl
#ifdef VR_DISTORTION
gTexCoord = distortion(gTexCoord);
#endif
```

This explains the visual test:

- `cardboard=1`: SBS plus Cardboard distortion.
- `cardboard=1&post_process=0`: still distorted.
- `cardboard=1&vr_stereo=0`: stereo disabled.
- `cardboard=1&vr_ar=1`: SBS preserved, distortion disabled.

## Input / Controls Pipeline

The camera feature defaults to orbit unless `navigation=fps`:

```js
indexManipulator: navigation === "fps" ? 1 : 0
```

But when `camera.webVR` becomes true, `setVR()` forces FPS:

```js
lastMode = camera.indexManipulator
fpsTouch = false
fpsWebVR = hasNativeVRSession
fpsDeviceOrientation = !hasNativeVRSession
indexManipulator = FPS
```

That was visually verified: with `cardboard=1&vr_ar=1`, SBS has no distortion, but mouse drag behaves as first-person look, and the wheel displays `Walking speed`.

Patch target:

- Keep `webVR` true and `vrConfig.doVR` true.
- Prevent `camera.setVR()` from setting `indexManipulator = FPS`.
- Disable `FPS_MANIPULATOR_WEBVR`, `FPS_MANIPULATOR_DEVICEORIENTATION`, `ORBIT_MANIPULATOR_WEBVR`, and `ORBIT_MANIPULATOR_DEVICEORIENTATION`.
- Leave regular orbit mouse/touch input enabled.

## Feasibility

Feasible with runtime JS injection, but not by only using public globals:

- Renderer/camera/scene objects are not exposed on `window`.
- The useful patch points are webpack module internals.
- The most robust non-invasive toggle found so far is `vr_ar=1` for no-distortion SBS.
- Full Orbit + SBS requires document-start patching before Sketchfab bundles execute, or a later internal-object discovery route if a stable reference to the viewer can be found.

Failure point in this environment:

- Codex in-app browser rejected write-style JS injection, so monkey patches could not be executed here.
- Static analysis found the patch points; visual URL-flag tests verified the distortion/stereo behavior.

## Verified Methods

### Verified: no-distortion SBS

Use:

```text
&cardboard=1&vr_stereo=1&vr_ar=1&tracking=0
```

This produces side-by-side stereo without the Cardboard barrel distortion.

### Verified: VR Settings can switch back to Orbit

In local Chrome/CDP, the `vr_ar=1` SBS page initially reported:

```text
Navigation   First Person
```

Then scripted clicks against Settings -> Navigation -> Orbit changed the DOM to:

```text
Navigation   Orbit
Click and hold to rotate
```

After a drag and wheel action, the setting remained `Orbit`; the previous `Walking speed` OSD did not reappear. This means the VR/FPS switch is not permanently locked. A runtime script can let Sketchfab initialize VR/SBS, then flip the normal settings/proxy path back to Orbit.

The Chrome probe also blocked three attempted `deviceorientation` listener registrations at document start.

### Verified: Chrome userscript prototype keeps Orbit

With `tools/sketchfab-sbs-inject.user.js` injected at document start in local Chrome/CDP:

- Settings state changed from `Navigation   First Person` to `Navigation   Orbit`.
- `window.__skfbSbs.forcedOrbit` became `1`.
- The page text changed from `SWITCH TO FULLSCREEN then place your phone into your Cardboard viewer` to `Click and hold to rotate`.
- `deviceorientation` listener registration was blocked three times.
- Webpack module `U6YP` was patched.
- All 10 active patch anchors hit:
  - force Orbit manipulator in VR
  - disable distortion branch
  - disable VR controller events
  - disable `WEBVR_NAVIGATION`
  - hide VR proxy on enable
  - hide VR proxy on visibility update
  - hide cursor and teleport ring
  - block teleport activation
  - half-SBS Cardboard projection
  - half-SBS native/XR projection

Visual Chrome result:

- Baseline/no-injection stayed in `First Person`.
- Baseline/no-injection center click plus drag produced the known failure mode: purple background, white reticle dots, and a VR navigation arrow.
- Full injection stayed in `Orbit`.
- Full injection center click did not teleport.
- Full injection drag rotated around the model instead of entering the empty VR navigation view.
- The visible VR reticle/teleport ring did not appear in the injected screenshots.

## Remaining Issues And Current Patch Status

### VR cursor and teleport

The reticle and teleport ring are not DOM elements. They are osg nodes under the VR proxy graph:

- `WebVR - Proxy`
- `WebVR - Cursor`
- `WebVR - Teleport`
- `WEBVR_NAVIGATION`

The key runtime behavior is:

```js
this.setProxyVisible(v)
this._cursorMT.setVisible(f)
this._teleportNode.setVisible(R && f && !O)
P = f && this._controllerMT.isTeleportPressed()
```

Here `f` is the webVR enable state, so switching the normal camera manipulator back to Orbit does not hide these nodes. Blocking `deviceorientation` also does not hide them. It only removes one input source.

Practical patch target:

```js
this.setProxyVisible(false)
this._cursorMT.setVisible(false)
this._teleportNode.setVisible(false)
P = false
```

This should remove the visible reticle/ring and prevent click-to-teleport, while leaving `vrConfig.doVR` enabled for stereo rendering. The stereo render path does not depend on the proxy node being visible.

Prototype status: `tools/sketchfab-sbs-inject.user.js` now patches these exact module anchors:

```js
this.setProxyVisible(v)
this._cursorMT.setVisible(f)
this._teleportNode.setVisible(R && f && !O)
P = f && this._controllerMT.isTeleportPressed()
this._inputManager.setEnable(WEBVR_NAVIGATION, true)
```

into hidden/disabled forms. After Tampermonkey testing still showed the reticle/teleport behavior, the prototype was hardened to patch the deeper class methods too:

```js
WebVRCursor.setVisible(false)
WebVRCursor.updateNodeVR() -> hidden/no-op
WebVRTeleport.setVisible(false)
WebVRTeleport.setHotspotHint() -> hidden/no-op
WebVRTeleport.updateNodeVR() -> hidden/no-op
Controller.onClick/onPointerUp/onMouseDown -> never set teleport/click pressed
Controller.isTeleportPressed()/isClickPressed() -> false
Controller frame pressed state -> false
```

This passed offline factory-rebuild validation on the current `U6YP` module: 17 active anchors hit, no rebuild failures. Chrome visual validation with the updated script showed SBS rendering with no visible center reticle; center click did not produce a teleport jump.

Tampermonkey testing later showed the large teleport landing ring could still appear even when all factory anchors reported as patched. That means the first patch only disabled part of the WebVR controller/cursor path, not the full WebVR navigation feature.

An intermediate attempt added two runtime guards:

```js
// Hide the large teleport landing ring even if the Teleport class was created
// before the module rewrite.
shaderSource(source.includes("uTextureTeleportCircle")) -> transparent fragment

// Prevent tap/click teleport while preserving drag orbit.
window capture: block canvas click / pointerup / mouseup / touchend only when
the pointer movement is below a small threshold.
```

That proved the ring uses the `uTextureTeleportCircle` shader, but it was the wrong final direction because it masked symptoms instead of disabling teleport.

Current direction: disable WebVR navigation at its internal registration and execution points. A later Tampermonkey result showed that factory patch hits can still be insufficient: the running page may already hold live WebVR view/controller/teleport instances created from the original module. The current script reports `scriptVersion: "2026-05-18-live-webvr-view-patch"` and intentionally leaves both runtime guards disabled:

```js
RUNTIME_CLICK_GUARD = false
TELEPORT_SHADER_GUARD = false
```

The current internal shutdown points are:

```js
4EEe.initWebVRNavigation(...) -> do not register WEBVR_NAVIGATION mappings
U6YP controller init -> skip Ve.Z.initWebVRNavigation(this)
setEventListeners/removeEventListeners -> keep WEBVR_NAVIGATION disabled
fadeForTeleportVR(...) -> no-op
doTeleport(...) -> no-op
teleport candidate state F/L/B -> false
teleport alpha target -> 0
teleportNode.updateNodeVR(...) -> called with disabled/zero alpha
```

The extra live-object layer captures webpack's runtime `require` by pushing a no-op chunk, then patches:

```js
req("4EEe").Z.initWebVRNavigation
req("U6YP").Z.find(getName() === "webVR").ViewListType[0].prototype
```

The WebVR view prototype wrapper patches the active instance before each update:

```js
view._controllerMT -> teleport/click/menu methods return false
view._teleportNode -> setVisible/updateNodeVR/setHotspotHint force hidden/no-op
view._cursorMT -> hidden/no-op
view.doTeleport / moveToHotspotWithIndex / goToNextHotspot / goToPrevHotspot -> no-op
postProcess.fadeForTeleportVR -> no-op
```

Chrome validation for this revision showed:

```js
window.__skfbSbs.patchedModules // ["4EEe", "U6YP"]
window.__skfbSbs.shaderPatches // []
window.__skfbSbs.blockedTeleportEvents // []
window.__skfbSbs.runtimePatches
// [
//   "export initWebVRNavigation",
//   "export webVR view prototype",
//   "live controller teleport methods",
//   "live teleport node methods",
//   "live cursor node methods",
//   "live webVR view methods",
//   "live postProcess teleport fade"
// ]
```

So the current prototype is no longer relying on shader hiding or click interception, and it verifies whether the live WebVR view/controller/teleport objects have actually been patched.

### Half-SBS output

Current Sketchfab Cardboard output is stereo SBS, but each eye is rendered with a projection that matches the per-eye viewport aspect. That makes each half look geometrically normal on a regular monitor.

For half-SBS display input, each eye should be stored in half the full image width. On a normal monitor this looks horizontally compressed; the target display then interprets/de-squeezes or remaps it.

In Sketchfab's render leaf, the per-eye viewport is already half the total framebuffer:

```js
viewport(0, 0, leftRenderWidth, leftRenderHeight)
viewport(leftRenderWidth, 0, leftRenderWidth, rightRenderHeight)
```

So the viewport split is not the main problem. The first prototype tried to change projection aspect. The Cardboard projection is built for `leftRenderWidth / leftRenderHeight`; half-SBS wants a projection as if the eye were rendered for the full output width, roughly:

```js
leftProjection[0] *= 0.5
rightProjection[0] *= 0.5
```

This widens the horizontal FOV used for each eye while keeping the same half-screen viewport, producing the horizontal compression expected by half-SBS. For asymmetric frusta, the center offset term should usually remain unchanged.

Changing projection uniforms globally proved fragile during later testing. A WebGL uniform-level attempt caused the left eye to lose material/shading on the coffee cup test model.

The current source finding is more useful: Cardboard render size does not come from `canvas.clientWidth`. The chain is:

```js
Cu.getScreenWidth()  = max(window.screen.width, window.screen.height) * devicePixelRatio
Cu.getScreenHeight() = min(window.screen.width, window.screen.height) * devicePixelRatio
new Au({ width: Cu.getScreenWidth(), height: Cu.getScreenHeight(), ... })
deviceInfo.getUndistortedViewportLeftEye()
webVR.getConfigCardboard()
cullConfig.leftRenderWidth = eye.renderWidth
cullConfig.framebufferWidth = 2 * leftRenderWidth
viewerOSGJS.computeCanvasSize() writes canvas.width = cullConfig.framebufferWidth
```

This source chain was useful, but directly doubling `eye.renderWidth` did not produce visible half-SBS. It changed the backing canvas from `1868 x 994` to `3736 x 994`, but the canvas was then CSS-scaled back to the same on-screen size, visually canceling the intended horizontal compression.

The current stable intervention point is still the live `getConfigCardboard()` path, but the patch changes only the fake-HMD projection matrices after `_frameData` is created:

```js
frameData.leftProjectionMatrix[0] *= 0.5
frameData.rightProjectionMatrix[0] *= 0.5
```

Verified in local Chrome on the coffee cup model:

```json
{
  "scriptVersion": "2026-05-18-live-cardboard-projection-half-sbs",
  "halfSbsMode": "live-cardboard-projection",
  "halfSbsProjectionPatch": {
    "mode": "cardboard-frameData-projection",
    "xScale": 0.5,
    "left": {
      "beforeM00": 1.2995447810825311,
      "afterM00": 0.6497723905412656
    },
    "right": {
      "beforeM00": 1.2995447810825311,
      "afterM00": 0.6497723905412656
    }
  },
  "canvas": {
    "width": 1868,
    "height": 994
  }
}
```

The canvas size remains unchanged while the cup becomes visibly horizontally compressed in Chrome. This avoids the WebGL uniform hook and limits the projection change to Sketchfab's fake Cardboard frame data.

### Verified: `vr_stereo=0` disables stereo

`&cardboard=1&vr_stereo=0` removes the SBS stereo path, so it is not useful for naked-eye 3D.

### Verified: `post_process=0` is not enough

`&cardboard=1&post_process=0` did not remove the Cardboard-shaped output in visual testing.

## Prototype Direction

Use `tools/sketchfab-sbs-inject.user.js` as a document-start prototype. It attempts four things:

- Normalizes URL params for Cardboard/SBS.
- Repeatedly switches the normal Settings navigation value back to Orbit.
- Blocks device-orientation / VR pose event registration.
- Hides the VR cursor/teleport proxy and blocks teleport activation.
- Changes Cardboard stereo output toward half-SBS by scaling fake-HMD projection `m00`.
- Patches shader source to remove VR distortion.
- Optionally patches current webpack bundle internals so camera VR mode stays on Orbit instead of FPS.

The script is version-sensitive because Sketchfab bundles are hashed and minified. It should be treated as a research prototype, not a production integration.

Useful local verification tools:

- `tools/chrome-sketchfab-probe.mjs`: DOM-aware CDP probe. It can switch Settings -> Navigation -> Orbit, but `Runtime.evaluate` can hang on Sketchfab when the page/service is unstable.
- `tools/chrome-sketchfab-visual-probe.mjs`: screenshot/input-only CDP probe with `SKFB_INJECT_MODE=none|full`. It avoids heavy runtime evaluation and logs page/network events.
- `tools/sketchfab-sbs-inject.user.js`: document-start injection prototype for Tampermonkey/Codex CDP.
