// ==UserScript==
// @name         Sketchfab Orbit SBS Prototype
// @match        https://sketchfab.com/models/*/embed*
// @match        https://www.sketchfab.com/models/*/embed*
// @match        https://sketchfab.com/*/embed*
// @match        https://www.sketchfab.com/*/embed*
// @run-at       document-start
// @inject-into  page
// @grant        none
// ==/UserScript==

/*
 * Research prototype for Sketchfab embed:
 * - reuse internal Cardboard/WebVR stereo rendering;
 * - suppress Cardboard lens distortion;
 * - keep Orbit manipulator instead of forced FPS/gyro controls.
 *
 * Verified separately:
 *   &cardboard=1&vr_ar=1 keeps SBS and disables distortion.
 *
 * The webpack patch below is intentionally narrow and version-sensitive.
 * It targets the bundle shape observed on 2026-05-17 for the supplied URL.
 */

(function sketchfabOrbitSbsPrototype() {
  'use strict';

  if (
    !/(^|\.)sketchfab\.com$/.test(location.hostname) ||
    !/\/embed\/?$/.test(location.pathname)
  ) {
    return;
  }

  const HIDE_VR_WIDGETS = true;
  const HALF_SBS = true;
  const RUNTIME_CLICK_GUARD = false;
  const TELEPORT_SHADER_GUARD = false;
  const SCRIPT_VERSION = '2026-05-18-safe-webpack-require';

  const state = window.__skfbSbs = window.__skfbSbs || {
    scriptVersion: SCRIPT_VERSION,
    blockedEvents: [],
    blockedTeleportEvents: [],
    forcedOrbit: 0,
    patchedModules: [],
    patchHits: [],
    runtimePatches: [],
    shaderPatches: [],
    startedAt: Date.now(),
    href: location.href,
    pageContext: true,
    webpackPushSeen: 0,
    lastOrbitAttempt: null
  };
  state.scriptVersion = SCRIPT_VERSION;
  state.blockedTeleportEvents = state.blockedTeleportEvents || [];
  state.runtimePatches = state.runtimePatches || [];
  state.shaderPatches = state.shaderPatches || [];

  const log = (...args) => console.info('[skfb-sbs]', ...args);
  const warn = (...args) => console.warn('[skfb-sbs]', ...args);

  function normalizeUrl() {
    const url = new URL(location.href);
    const params = url.searchParams;

    const desired = {
      autostart: '1',
      cardboard: '1',
      internal: '1',
      tracking: '0',
      vr_stereo: '1',
      navigation: 'orbit'
    };

    let changed = false;
    for (const [key, value] of Object.entries(desired)) {
      if (params.get(key) !== value) {
        params.set(key, value);
        changed = true;
      }
    }

    /*
     * This is the verified low-friction no-distortion switch.
     * If you prefer preserving the normal VR background/floor behavior, comment
     * this out and rely on the shader/doDistortionVR patches below instead.
     */
    if (params.get('vr_ar') !== '1') {
      params.set('vr_ar', '1');
      changed = true;
    }

    if (changed) {
      location.replace(url.toString());
    }
  }

  function patchEventInputs() {
    const blocked = new Set([
      'deviceorientation',
      'devicemotion',
      'vrdisplayposechanged'
    ]);
    const add = EventTarget.prototype.addEventListener;
    EventTarget.prototype.addEventListener = function patchedAddEventListener(type, listener, options) {
      if (blocked.has(String(type).toLowerCase())) {
        state.blockedEvents.push(String(type).toLowerCase());
        log('blocked listener', type);
        return;
      }
      return add.call(this, type, listener, options);
    };
  }

  function patchTeleportClickEvents() {
    let mouseDown = null;
    let touchDown = null;

    function isCanvasEvent(event) {
      const canvas = document.querySelector('canvas.canvas, canvas');
      if (!canvas) return false;
      const path = typeof event.composedPath === 'function' ? event.composedPath() : [];
      if (path.includes(canvas) || event.target === canvas) return true;

      const point =
        event.changedTouches && event.changedTouches[0] ||
        event.touches && event.touches[0] ||
        event;
      if (typeof point.clientX !== 'number' || typeof point.clientY !== 'number') return false;

      const rect = canvas.getBoundingClientRect();
      return (
        point.clientX >= rect.left &&
        point.clientX <= rect.right &&
        point.clientY >= rect.top &&
        point.clientY <= rect.bottom
      );
    }

    function isUiEvent(event) {
      const path = typeof event.composedPath === 'function' ? event.composedPath() : [];
      return path.some(node => {
        if (!node || node === window || node === document || !node.classList) return false;
        return (
          node.classList.contains('controls') ||
          node.classList.contains('control') ||
          node.classList.contains('settings') ||
          node.hasAttribute('data-setting')
        );
      });
    }

    function block(event, reason) {
      state.blockedTeleportEvents.push({
        type: event.type,
        reason,
        time: Date.now()
      });
      event.preventDefault();
      event.stopImmediatePropagation();
      return false;
    }

    function smallMouseMove(event) {
      if (!mouseDown) return true;
      return (
        Math.abs(event.clientX - mouseDown.x) +
        Math.abs(event.clientY - mouseDown.y)
      ) < 8;
    }

    function smallTouchMove(event) {
      if (!touchDown) return true;
      const touch = event.changedTouches && event.changedTouches[0];
      if (!touch) return true;
      return (
        Math.abs(touch.clientX - touchDown.x) +
        Math.abs(touch.clientY - touchDown.y)
      ) < 12;
    }

    window.addEventListener('mousedown', event => {
      if (event.button === 0 && isCanvasEvent(event) && !isUiEvent(event)) {
        mouseDown = { x: event.clientX, y: event.clientY, time: Date.now() };
      } else {
        mouseDown = null;
      }
    }, true);

    window.addEventListener('mouseup', event => {
      if (event.button === 0 && isCanvasEvent(event) && !isUiEvent(event) && smallMouseMove(event)) {
        block(event, 'canvas click-to-teleport mouseup');
      }
      mouseDown = null;
    }, true);

    window.addEventListener('click', event => {
      if (isCanvasEvent(event) && !isUiEvent(event)) {
        block(event, 'canvas click-to-teleport click');
      }
    }, true);

    window.addEventListener('pointerdown', event => {
      if (event.button === 0 && isCanvasEvent(event) && !isUiEvent(event)) {
        mouseDown = { x: event.clientX, y: event.clientY, time: Date.now() };
      }
    }, true);

    window.addEventListener('pointerup', event => {
      if (event.button === 0 && isCanvasEvent(event) && !isUiEvent(event) && smallMouseMove(event)) {
        block(event, 'canvas click-to-teleport pointerup');
      }
      mouseDown = null;
    }, true);

    window.addEventListener('touchstart', event => {
      if (event.touches && event.touches.length === 1 && isCanvasEvent(event) && !isUiEvent(event)) {
        const touch = event.touches[0];
        touchDown = { x: touch.clientX, y: touch.clientY, time: Date.now() };
      } else {
        touchDown = null;
      }
    }, true);

    window.addEventListener('touchend', event => {
      if (isCanvasEvent(event) && !isUiEvent(event) && smallTouchMove(event)) {
        block(event, 'canvas tap-to-teleport touchend');
      }
      touchDown = null;
    }, true);
  }

  function clickLikeUser(el) {
    if (!el) return false;
    for (const type of ['mousedown', 'mouseup', 'click']) {
      el.dispatchEvent(new MouseEvent(type, {
        bubbles: true,
        cancelable: true,
        view: window
      }));
    }
    return true;
  }

  function forceOrbitViaSettings() {
    const viewerMode = document.querySelector('[data-setting="viewerMode"]');
    const value = viewerMode && viewerMode.querySelector('.setting-value');
    if (value && value.getAttribute('data-value') === '0') return true;

    const orbit =
      document.querySelector('[data-setting="viewerMode"] [data-value="0"]') ||
      [...document.querySelectorAll('li, a, button')]
        .find(el => /^\s*Orbit\s*$/.test(el.textContent || ''));

    if (orbit) {
      const ok = clickLikeUser(orbit);
      if (ok) {
        state.forcedOrbit += 1;
        log('requested Settings -> Navigation -> Orbit');
      }
      return ok;
    }

    const navigation =
      document.querySelector('[data-setting="viewerMode"]') ||
      [...document.querySelectorAll('.setting, [data-setting], li, a, button')]
        .find(el => /Navigation|Orbit|First Person/.test(el.textContent || ''));

    if (clickLikeUser(navigation)) return false;

    const settings =
      document.querySelector('.control.settings') ||
      document.querySelector('.settings.control') ||
      document.querySelector('[title*="Settings"]') ||
      [...document.querySelectorAll('.control, button, a')]
        .find(el => /settings/i.test(el.title || el.textContent || el.className));

    clickLikeUser(settings);
    return false;
  }

  function keepOrbitEnabled() {
    let attempts = 0;
    const timer = setInterval(() => {
      attempts += 1;
      state.lastOrbitAttempt = {
        attempts,
        readyState: document.readyState,
        hasViewerMode: !!document.querySelector('[data-setting="viewerMode"]'),
        hasOrbitOption: !!(
          document.querySelector('[data-setting="viewerMode"] [data-value="0"]') ||
          [...document.querySelectorAll('li, a, button')]
            .find(el => /^\s*Orbit\s*$/.test(el.textContent || ''))
        )
      };
      const ok = forceOrbitViaSettings();
      if (ok || attempts > 120) clearInterval(timer);
    }, 500);
  }

  function patchWebGLDistortion() {
    function patchContext(Ctor) {
      if (!Ctor || !Ctor.prototype || Ctor.prototype.__skfbSbsPatched) return;
      const proto = Ctor.prototype;
      const shaderSource = proto.shaderSource;
      if (typeof shaderSource === 'function') {
        proto.shaderSource = function patchedShaderSource(shader, source) {
          let next = String(source);
          if (
            next.includes('VR_DISTORTION') ||
            next.includes('uHmdWarpParam') ||
            next.includes('uChromAbParam') ||
            next.includes('distortion(gTexCoord)')
          ) {
            next = next
              .replace(/#define\s+VR_DISTORTION/g, '/* VR_DISTORTION disabled by skfb-sbs */')
              .replace(/gTexCoord\s*=\s*distortion\(gTexCoord\)\s*;/g, '/* distortion disabled */')
              .replace(/vec4\s+VRpostProcessing\s*\([^]*?\n\}/g, 'vec4 VRpostProcessing() { return texture2D(Texture0, vTexCoord0); }');
            log('patched distortion shader');
          }
          if (TELEPORT_SHADER_GUARD && next.includes('uTextureTeleportCircle')) {
            next = next.replace(
              /void\s+main\s*\(\s*void\s*\)\s*\{[^]*?\}/,
              'void main(void) { gl_FragColor = vec4(0.0); }'
            );
            state.shaderPatches.push({
              type: 'teleport-circle',
              time: Date.now()
            });
            log('patched teleport circle shader');
          }
          return shaderSource.call(this, shader, next);
        };
      }
      Object.defineProperty(proto, '__skfbSbsPatched', { value: true });
    }

    patchContext(window.WebGLRenderingContext);
    patchContext(window.WebGL2RenderingContext);
  }

  function patchOsgVrWidgetNodes() {
    const hiddenNames = new Set([
      'WebVR - Proxy',
      'WebVR - Cursor',
      'WebVR - Teleport'
    ]);

    function suppressNode(node, name) {
      if (!node || node.__skfbSbsSuppressedVrWidget) return;
      if (!hiddenNames.has(name || (node.getName && node.getName()))) return;

      Object.defineProperty(node, '__skfbSbsSuppressedVrWidget', { value: true });
      state.patchHits.push({
        moduleId: 'osg-runtime',
        needle: `suppress ${name}`
      });

      if (typeof node.setNodeMask === 'function') {
        const setNodeMask = node.setNodeMask;
        node.setNodeMask = function patchedSetNodeMask() {
          return setNodeMask.call(this, 0);
        };
        setNodeMask.call(node, 0);
      }

      if (typeof node.setVisible === 'function') {
        const setVisible = node.setVisible;
        node.setVisible = function patchedSetVisible() {
          return setVisible.call(this, false);
        };
        setVisible.call(node, false);
      }

      log('suppressed VR widget node', name);
    }

    function install() {
      const osg = window.osg || (window.osgViewer && window.osgViewer.osg);
      const Node = osg && osg.Node;
      if (!Node || !Node.prototype || Node.prototype.__skfbSbsVrNamePatched) return false;

      const setName = Node.prototype.setName;
      if (typeof setName !== 'function') return false;

      Node.prototype.setName = function patchedSetName(name) {
        const result = setName.apply(this, arguments);
        suppressNode(this, name);
        return result;
      };

      Object.defineProperty(Node.prototype, '__skfbSbsVrNamePatched', { value: true });
      log('patched osg.Node#setName for VR widget suppression');
      return true;
    }

    if (install()) return;

    let attempts = 0;
    const timer = setInterval(() => {
      attempts += 1;
      if (install() || attempts > 120) clearInterval(timer);
    }, 250);
  }

  function rewriteFactory(factory, moduleId) {
    if (typeof factory !== 'function') return factory;

    let src = Function.prototype.toString.call(factory);
    let changed = false;

    const replacements = [
      [
        'i.setEnable(pt.FPS_MANIPULATOR_TOUCH,!1),i.setEnable(pt.FPS_MANIPULATOR_WEBVR,n),i.setEnable(pt.FPS_MANIPULATOR_DEVICEORIENTATION,!n),this.model.set("indexManipulator",Ee.FPS)',
        'i.setEnable(pt.FPS_MANIPULATOR_TOUCH,!1),i.setEnable(pt.FPS_MANIPULATOR_WEBVR,!1),i.setEnable(pt.FPS_MANIPULATOR_DEVICEORIENTATION,!1),i.setEnable(pt.ORBIT_MANIPULATOR_WEBVR,!1),i.setEnable(pt.ORBIT_MANIPULATOR_DEVICEORIENTATION,!1),this.model.set("indexManipulator",Ee.ORBIT)'
      ],
      [
        'e.set("doDistortionVR",t),t&&',
        'e.set("doDistortionVR",false),false&&'
      ],
      [
        'if(e.set("doDistortionVR",t),t){',
        'if(e.set("doDistortionVR",false),false){'
      ],
      [
        'this._controllerMT.setListenToEvents(e)',
        'this._controllerMT.setListenToEvents(false)'
      ],
      [
        'this._inputManager.setEnable(Mc.WEBVR_NAVIGATION,!0)',
        'this._inputManager.setEnable(Mc.WEBVR_NAVIGATION,!1)'
      ],
      [
        'initWebVRNavigation:function(e){this._manager.group(u.WEBVR_NAVIGATION).addMappings({onPointerDown:"touchstart 1",onPointerUp:["touchend 1","mouseup 0","mouseup 1"],displayMenu:["mouseup 2"],onMouseDown:"mousedown"},e),this._manager.setEnable(u.WEBVR_NAVIGATION,!1)}',
        'initWebVRNavigation:function(e){this._manager.setEnable(u.WEBVR_NAVIGATION,!1)}'
      ],
      [
        'Ve.Z.initWebVRNavigation(this),this._ctrlLeft=.32,this._ctrlFwrd=.55',
        'this._inputManager.setEnable(Mc.WEBVR_NAVIGATION,!1),this._ctrlLeft=.32,this._ctrlFwrd=.55'
      ],
      [
        'setEventListeners:function(){this._model.get("hasNativeHMD")?window.addEventListener("click",this._cbClick):this._inputManager.setEnable(Mc.WEBVR_NAVIGATION,!0)},removeEventListeners:function(){this._model.get("hasNativeHMD")?window.addEventListener("click",this._cbClick):this._inputManager.setEnable(Mc.WEBVR_NAVIGATION,!1)},onClick:function(){this._teleportPressed=!0,this._clickPressed=!0}',
        'setEventListeners:function(){this._inputManager.setEnable(Mc.WEBVR_NAVIGATION,!1)},removeEventListeners:function(){this._inputManager.setEnable(Mc.WEBVR_NAVIGATION,!1)},onClick:function(){this._teleportPressed=!1,this._clickPressed=!1}'
      ],
      [
        'setEventListeners:function(){this._model.get("hasNativeHMD")?window.addEventListener("click",this._cbClick):this._inputManager.setEnable(Mc.WEBVR_NAVIGATION,!1)},removeEventListeners:function(){this._model.get("hasNativeHMD")?window.addEventListener("click",this._cbClick):this._inputManager.setEnable(Mc.WEBVR_NAVIGATION,!1)},onClick:function(){this._teleportPressed=!0,this._clickPressed=!0}',
        'setEventListeners:function(){this._inputManager.setEnable(Mc.WEBVR_NAVIGATION,!1)},removeEventListeners:function(){this._inputManager.setEnable(Mc.WEBVR_NAVIGATION,!1)},onClick:function(){this._teleportPressed=!1,this._clickPressed=!1}'
      ],
      [
        'fadeForTeleportVR:function(e){this.set("vrStartFade",!0),this.set("vrFadeCallback",e)}',
        'fadeForTeleportVR:function(e){return}'
      ],
      [
        'doTeleport:function(e,t,i){var n=this._viewer.getFeaturesManager();e[2]+=this.getEyeLevel(),t[2]+=this.getEyeLevel(),n.focusOnTargetAndEye(t,e),this.toggleHotspotCard(i)}',
        'doTeleport:function(e,t,i){return}'
      ]
    ];

    if (HIDE_VR_WIDGETS) {
      replacements.push(
        [
          'this.setProxyVisible(v);var _=g.background.getModel();',
          'this.setProxyVisible(false);var _=g.background.getModel();'
        ],
        [
          'this.setProxyVisible(e),this.requestData(e,t),this.updateDistortion()',
          'this.setProxyVisible(false),this.requestData(e,t),this.updateDistortion()'
        ],
        [
          'this._cursorMT.setVisible(f),this._teleportNode.setVisible(R&&f&&!O);',
          'this._cursorMT.setVisible(false),this._teleportNode.setVisible(false);'
        ],
        [
          'P=f&&this._controllerMT.isTeleportPressed()',
          'P=false'
        ],
        [
          'var F=R&&e[2]>.5,L=w?w.canTeleport():F,V=R&&-1!==R._nodePath.indexOf(this._floorMT),B=w?w.canTeleport():F&&(!this.model.isDiorama()||V);',
          'var F=false,L=false,V=false,B=false;'
        ],
        [
          'this._interpolatorAlpha.setTarget(B?1:0),this._interpolatorAlpha.update(C.getDeltaTime())',
          'this._interpolatorAlpha.setTarget(0),this._interpolatorAlpha.update(C.getDeltaTime())'
        ],
        [
          'this._teleportNode.updateNodeVR(H,R,C,o,x,Z,e)',
          'this._teleportNode.updateNodeVR(0,false,C,o,x,Z,e)'
        ],
        [
          'setVisible:function(e){this.setNodeMask(e?Pe.Z.NO_PICK:0)},setColor:function(e){oc.vec3.copy(this._uColor.getInternalArray(),e)},updateNodeVR:function(){var e=oc.vec3.create(),t=oc.quat.create();return function(i,n,r,a){var o=this.getMatrix();',
          'setVisible:function(e){this.setNodeMask(0)},setColor:function(e){oc.vec3.copy(this._uColor.getInternalArray(),e)},updateNodeVR:function(){var e=oc.vec3.create(),t=oc.quat.create();return function(i,n,r,a){this.setNodeMask(0);this._uAlpha&&this._uAlpha.setFloat(0);return;var o=this.getMatrix();'
        ],
        [
          'setVisible:function(e){this.setNodeMask(e?Pe.Z.NO_PICK:0)},setHotspotHint:function(){var e=ic.vec3.create()',
          'setVisible:function(e){this.setNodeMask(0)},setHotspotHint:function(){this._hotspotHintMT&&this._hotspotHintMT.setNodeMask(0);return;var e=ic.vec3.create()'
        ],
        [
          'return function(i,n,r,a,o,s,l){var u=this._teleportMT.getMatrix();',
          'return function(i,n,r,a,o,s,l){this.setNodeMask(0);this._hotspotHintMT&&this._hotspotHintMT.setNodeMask(0);return;var u=this._teleportMT.getMatrix();'
        ],
        [
          'onClick:function(){this._teleportPressed=!0,this._clickPressed=!0}',
          'onClick:function(){this._teleportPressed=!1,this._clickPressed=!1}'
        ],
        [
          'onPointerDown:function(){jr.default.any&&!this._activeController&&(this._pointerClicked=!0)},onPointerUp:function(e){this._pointerClicked=!1,jr.default.any||Math.abs(e.canvasX-this._canvasX)<5&&Math.abs(e.canvasY-this._canvasY)<5&&(this._clickPressed=!0,this._teleportPressed=!0)},displayMenu:function(){this._menuPressed=!0},onMouseDown:function(e){jr.default.any&&!this._activeController&&this._model.get("hasNativeHMD")&&(this._teleportPressed=!0),jr.default.any||(this._canvasX=e.canvasX,this._canvasY=e.canvasY)}',
          'onPointerDown:function(){this._pointerClicked=!1},onPointerUp:function(e){this._pointerClicked=!1,this._clickPressed=!1,this._teleportPressed=!1},displayMenu:function(){this._menuPressed=!1},onMouseDown:function(e){this._teleportPressed=!1,this._clickPressed=!1,jr.default.any||(this._canvasX=e.canvasX,this._canvasY=e.canvasY)}'
        ],
        [
          'isMenuPressed:function(){return this._menuPressedFrame},isTeleportPressed:function(){return this._teleportPressedFrame},isClickPressed:function(){return this._clickPressedFrame}',
          'isMenuPressed:function(){return false},isTeleportPressed:function(){return false},isClickPressed:function(){return false}'
        ],
        [
          'this._menuPressedFrame=this._menuPressed,this._clickPressedFrame=this._clickPressed,this._teleportPressedFrame=this._teleportPressed',
          'this._menuPressedFrame=false,this._clickPressedFrame=false,this._teleportPressedFrame=false'
        ]
      );
    }

    if (HALF_SBS) {
      replacements.push(
        [
          'this._updateCullConfig(g,c,h),U.Z.draw(),l.framebufferWidth=2*l.leftRenderWidth',
          'this._updateCullConfig(g,c,h),l.leftProjection[0]*=.5,l.rightProjection[0]*=.5,U.Z.draw(),l.framebufferWidth=2*l.leftRenderWidth'
        ],
        [
          'computeEyesOffsetViewMatrices(l.leftOffsetView,l.rightOffsetView,e),this._viewer.getInputManager().setParam("worldFactor",e),U.Z.draw()',
          'computeEyesOffsetViewMatrices(l.leftOffsetView,l.rightOffsetView,e),l.leftProjection[0]*=.5,l.rightProjection[0]*=.5,this._viewer.getInputManager().setParam("worldFactor",e),U.Z.draw()'
        ]
      );
    }

    for (const [from, to] of replacements) {
      if (src.includes(from)) {
        src = src.split(from).join(to);
        state.patchHits.push({
          moduleId,
          needle: from.slice(0, 80)
        });
        changed = true;
      }
    }

    if (!changed) return factory;

    try {
      const patched = (0, eval)('(' + src + ')');
      state.patchedModules.push(moduleId);
      log('patched webpack module', moduleId);
      return patched;
    } catch (error) {
      warn('failed to rebuild module', moduleId, error);
      return factory;
    }
  }

  function patchWebpackChunks() {
    const chunkName = 'webpackChunksketchfab';
    const chunks = window[chunkName] = window[chunkName] || [];
    const originalPush = chunks.push.bind(chunks);

    function patchChunk(chunk) {
      const modules = chunk && chunk[1];
      if (modules && typeof modules === 'object') {
        for (const id of Object.keys(modules)) {
          modules[id] = rewriteFactory(modules[id], id);
        }
      }
      return chunk;
    }

    chunks.push = function patchedWebpackPush(...items) {
      state.webpackPushSeen += items.length;
      return originalPush(...items.map(patchChunk));
    };

    for (let i = 0; i < chunks.length; i += 1) {
      patchChunk(chunks[i]);
    }
  }

  function patchWebpackRuntimeExports() {
    function record(label) {
      if (!state.runtimePatches.includes(label)) {
        state.runtimePatches.push(label);
        log('runtime patch', label);
      }
    }

    function getWebpackRequire() {
      if (window.__skfbWebpackRequire) return window.__skfbWebpackRequire;
      const chunks = window.webpackChunksketchfab;
      if (!chunks || typeof chunks.push !== 'function') return null;
      try {
        chunks.push([[Date.now() + Math.floor(Math.random() * 100000)], {}, req => {
          window.__skfbWebpackRequire = req;
        }]);
      } catch (error) {
        warn('failed to capture webpack require', error);
      }
      return window.__skfbWebpackRequire || null;
    }

    function hasWebpackModule(req, moduleId) {
      if (!req) return false;
      if (req.m && typeof req.m === 'object') {
        return Object.prototype.hasOwnProperty.call(req.m, moduleId);
      }
      return true;
    }

    function safeWebpackRequire(req, moduleId) {
      if (!hasWebpackModule(req, moduleId)) return null;
      try {
        return req(moduleId);
      } catch (error) {
        const message = String(error && (error.message || error));
        if (
          message.includes("reading 'call'") ||
          message.includes('reading "call"') ||
          message.includes('Cannot find module') ||
          message.includes('is not a function')
        ) {
          return null;
        }
        throw error;
      }
    }

    function patchController(controller) {
      if (!controller || controller.__skfbSbsTeleportDisabled) return;
      Object.defineProperty(controller, '__skfbSbsTeleportDisabled', { value: true });

      controller.onClick = function disabledOnClick() {
        this._teleportPressed = false;
        this._clickPressed = false;
      };
      controller.onPointerDown = function disabledPointerDown() {
        this._pointerClicked = false;
      };
      controller.onPointerUp = function disabledPointerUp() {
        this._pointerClicked = false;
        this._clickPressed = false;
        this._teleportPressed = false;
      };
      controller.onMouseDown = function disabledMouseDown(event) {
        this._teleportPressed = false;
        this._clickPressed = false;
        if (event) {
          this._canvasX = event.canvasX;
          this._canvasY = event.canvasY;
        }
      };
      controller.updatePointerClick = function disabledPointerClick() {
        this._pointerClicked = false;
        this._timeSinceLastPointerClick = 0;
      };
      controller.onButtonUp = function disabledButtonUp() {
        this._clickPressed = false;
        this._teleportPressed = false;
        this._menuPressed = false;
      };
      controller.isTeleportPressed = function disabledTeleportPressed() { return false; };
      controller.isClickPressed = function disabledClickPressed() { return false; };
      controller.isMenuPressed = function disabledMenuPressed() { return false; };
      controller.setListenToEvents = function disabledListenToEvents() {
        if (this._inputManager && window.__skfbWebpackRequire) {
          try {
            const inputModule = safeWebpackRequire(window.__skfbWebpackRequire, 'aqzA');
            const inputGroups = inputModule && inputModule.Z && inputModule.Z.InputGroups;
            if (!inputGroups) return;
            this._inputManager.setEnable(inputGroups.WEBVR_NAVIGATION, false);
          } catch {}
        }
      };
      controller.removeEventListeners && controller.removeEventListeners();
      record('live controller teleport methods');
    }

    function patchTeleportNode(node) {
      if (!node || node.__skfbSbsTeleportDisabled) return;
      Object.defineProperty(node, '__skfbSbsTeleportDisabled', { value: true });

      if (typeof node.setNodeMask === 'function') {
        const setNodeMask = node.setNodeMask;
        node.setNodeMask = function disabledTeleportNodeMask() {
          return setNodeMask.call(this, 0);
        };
        setNodeMask.call(node, 0);
      }
      node.setVisible = function disabledTeleportVisible() {
        if (typeof this.setNodeMask === 'function') this.setNodeMask(0);
      };
      node.setHotspotHint = function disabledHotspotHint() {
        if (this._hotspotHintMT && typeof this._hotspotHintMT.setNodeMask === 'function') {
          this._hotspotHintMT.setNodeMask(0);
        }
      };
      node.updateWorldNormal = function disabledWorldNormal() {};
      node.updateNodeVR = function disabledTeleportUpdate() {
        if (typeof this.setNodeMask === 'function') this.setNodeMask(0);
        if (this._hotspotHintMT && typeof this._hotspotHintMT.setNodeMask === 'function') {
          this._hotspotHintMT.setNodeMask(0);
        }
      };
      if (node._teleportMT && typeof node._teleportMT.setNodeMask === 'function') node._teleportMT.setNodeMask(0);
      if (node._hotspotHintMT && typeof node._hotspotHintMT.setNodeMask === 'function') node._hotspotHintMT.setNodeMask(0);
      record('live teleport node methods');
    }

    function patchCursorNode(node) {
      if (!node || node.__skfbSbsCursorDisabled) return;
      Object.defineProperty(node, '__skfbSbsCursorDisabled', { value: true });
      if (typeof node.setNodeMask === 'function') node.setNodeMask(0);
      node.setVisible = function disabledCursorVisible() {
        if (typeof this.setNodeMask === 'function') this.setNodeMask(0);
      };
      node.updateNodeVR = function disabledCursorUpdate() {
        if (typeof this.setNodeMask === 'function') this.setNodeMask(0);
        if (this._uAlpha && typeof this._uAlpha.setFloat === 'function') this._uAlpha.setFloat(0);
      };
      record('live cursor node methods');
    }

    function patchWebVrView(view) {
      if (!view) return;
      patchController(view._controllerMT);
      patchTeleportNode(view._teleportNode);
      patchCursorNode(view._cursorMT);

      if (view._interpolatorAlpha && typeof view._interpolatorAlpha.setTarget === 'function') {
        try { view._interpolatorAlpha.setTarget(0); } catch {}
      }

      if (!view.__skfbSbsViewMethodsDisabled) {
        Object.defineProperty(view, '__skfbSbsViewMethodsDisabled', { value: true });
        view.doTeleport = function disabledDoTeleport() {};
        view.moveToHotspotWithIndex = function disabledMoveToHotspotWithIndex() {};
        view.goToNextHotspot = function disabledNextHotspot() {};
        view.goToPrevHotspot = function disabledPrevHotspot() {};
        view.setProxyVisible = function disabledProxyVisible() {
          if (this._proxyVR && typeof this._proxyVR.setNodeMask === 'function') this._proxyVR.setNodeMask(0);
        };
        record('live webVR view methods');
      }

      try {
        const postProcess = view._viewer && view._viewer.getFeatures &&
          view._viewer.getFeatures().postProcess &&
          view._viewer.getFeatures().postProcess.getModel();
        if (postProcess && !postProcess.__skfbSbsTeleportFadeDisabled) {
          Object.defineProperty(postProcess, '__skfbSbsTeleportFadeDisabled', { value: true });
          postProcess.fadeForTeleportVR = function disabledTeleportFade() {};
          record('live postProcess teleport fade');
        }
      } catch {}
    }

    function patchExports() {
      const req = getWebpackRequire();
      if (!req) return false;

      try {
        const inputModule = safeWebpackRequire(req, '4EEe');
        const inputInit = inputModule && inputModule.Z;
        if (inputInit && !inputInit.__skfbSbsWebVrNavigationPatched) {
          inputInit.initWebVRNavigation = function disabledInitWebVRNavigation() {
            if (this._manager) {
              try {
                const inputGroupsModule = safeWebpackRequire(req, 'aqzA');
                const groups = inputGroupsModule && inputGroupsModule.Z && inputGroupsModule.Z.InputGroups;
                if (!groups) return;
                this._manager.setEnable(groups.WEBVR_NAVIGATION, false);
              } catch {}
            }
          };
          Object.defineProperty(inputInit, '__skfbSbsWebVrNavigationPatched', { value: true });
          record('export initWebVRNavigation');
        }
      } catch {}

      try {
        const featureModule = safeWebpackRequire(req, 'U6YP');
        const features = featureModule && featureModule.Z;
        if (!features) return false;
        const webVrFeature = Array.isArray(features) && features.find(feature => {
          try { return feature && feature.getName && feature.getName() === 'webVR'; } catch { return false; }
        });
        const WebVrView = webVrFeature && webVrFeature.ViewListType && webVrFeature.ViewListType[0];
        const proto = WebVrView && WebVrView.prototype;
        if (!proto) return false;

        if (!proto.__skfbSbsWebVrViewPatched) {
          const update = proto.update;
          if (typeof update === 'function') {
            proto.update = function patchedWebVrUpdate() {
              patchWebVrView(this);
              return update.apply(this, arguments);
            };
          }
          proto.doTeleport = function disabledPrototypeDoTeleport() {};
          proto.moveToHotspotWithIndex = function disabledPrototypeMoveToHotspot() {};
          proto.goToNextHotspot = function disabledPrototypeNextHotspot() {};
          proto.goToPrevHotspot = function disabledPrototypePrevHotspot() {};
          Object.defineProperty(proto, '__skfbSbsWebVrViewPatched', { value: true });
          record('export webVR view prototype');
        }
        return true;
      } catch (error) {
        warn('failed to patch WebVR exports', error);
        return false;
      }
    }

    let attempts = 0;
    const timer = setInterval(() => {
      attempts += 1;
      if (patchExports() || attempts > 160) clearInterval(timer);
    }, 250);
    patchExports();
  }

  normalizeUrl();
  patchEventInputs();
  if (RUNTIME_CLICK_GUARD) patchTeleportClickEvents();
  patchWebGLDistortion();
  patchOsgVrWidgetNodes();
  patchWebpackChunks();
  patchWebpackRuntimeExports();
  keepOrbitEnabled();
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', keepOrbitEnabled, { once: true });
    window.addEventListener('load', keepOrbitEnabled, { once: true });
  } else {
    keepOrbitEnabled();
  }
  log('prototype installed');
  setTimeout(() => {
    if (!state.patchedModules.length) {
      warn('no webpack module patched; Tampermonkey may not be running in page context early enough', state);
    }
  }, 8000);
})();
