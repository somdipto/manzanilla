/* Manzanilla's real WebGL reception scene. Dependencies are bundled locally in
 * vendor/three.js; the original procedural character and bell are character.js.
 * Public API spelling is retained for the native/guest bridge. */
(function () {
  'use strict';

  const canvas = document.getElementById('scene');
  const fallback = document.getElementById('scene-fallback');
  if (!canvas) return;

  let currentPage = 'idle';
  let currentPhase = '';
  let hostVisible = true;
  let pageHidden = false;
  let disposed = false;
  let contextLost = false;
  let scene = null;

  function unavailable(error) {
    if (fallback) fallback.hidden = false;
    if (error) console.warn('Reception 3D scene unavailable', error);
  }

  function visible() {
    return hostVisible && !document.hidden && !pageHidden && !contextLost;
  }

  function mount() {
    if (disposed || pageHidden || contextLost || scene) return;
    try {
      scene = createScene();
      scene.setState(currentPage, currentPhase);
      scene.setVisible(visible());
      if (fallback) fallback.hidden = true;
    } catch (error) {
      unavailable(error);
    }
  }

  function unmount() {
    scene?.dispose();
    scene = null;
  }

  function visibilityChanged() {
    scene?.setVisible(visible());
  }

  function pageHide() {
    pageHidden = true;
    unmount();
  }

  function pageShow() {
    pageHidden = false;
    mount();
    visibilityChanged();
  }

  function contextWasLost(event) {
    event.preventDefault();
    contextLost = true;
    visibilityChanged();
    unavailable();
  }

  function contextWasRestored() {
    contextLost = false;
    unmount();
    mount();
  }

  window.manzaniaScene = Object.freeze({
    setState(page, phase) {
      currentPage = page;
      currentPhase = phase;
      scene?.setState(page, phase);
    },
    pressBell() { scene?.pressBell(); },
    // Only output/playback energy is accepted by this API, never mic level.
    energy(level) { scene?.energy(level); },
    setVisible(isVisible) {
      hostVisible = Boolean(isVisible);
      visibilityChanged();
    },
    dispose() {
      if (disposed) return;
      disposed = true;
      unmount();
      document.removeEventListener('visibilitychange', visibilityChanged);
      window.removeEventListener('pagehide', pageHide);
      window.removeEventListener('pageshow', pageShow);
      canvas.removeEventListener('webglcontextlost', contextWasLost);
      canvas.removeEventListener('webglcontextrestored', contextWasRestored);
    }
  });

  function createScene() {
    const THREE = window.Manzanilla3DVendor;
    const character = window.ManzanillaCharacter;
    if (!THREE || !character) throw new Error('Local 3D assets are unavailable');

    const world = new THREE.Scene();
    const renderer = new THREE.WebGLRenderer({
      canvas, antialias: true, alpha: true, powerPreference: 'low-power'
    });
    let environment = null;
    let resizeObserver = null;
    let themeObserver = null;
    let motionChanged = null;
    const reducedMotion = window.matchMedia('(prefers-reduced-motion: reduce)');
    let raf = 0;
    let alive = true;
    let isVisible = false;
    let dirty = true;
    let hasSize = false;
    let lastFrame = null;
    let elapsed = 0;
    let page = 'idle';
    let phase = '';
    let bellPressedAt = -Infinity;
    let playbackTarget = 0;
    let playbackLevel = 0;
    let playbackSampleAt = -Infinity;

    function dispose() {
      if (!alive) return;
      alive = false;
      cancelAnimationFrame(raf);
      raf = 0;
      resizeObserver?.disconnect();
      themeObserver?.disconnect();
      reducedMotion.removeEventListener('change', motionChanged);
      const geometries = new Set();
      const materials = new Set();
      world.traverse(object => {
        if (object.geometry) geometries.add(object.geometry);
        if (object.material) {
          const list = Array.isArray(object.material) ? object.material : [object.material];
          for (const material of list) materials.add(material);
        }
        object.shadow?.dispose();
      });
      for (const geometry of geometries) geometry.dispose();
      for (const material of materials) material.dispose();
      world.environment = null;
      environment?.dispose();
      renderer.dispose();
    }

    try {
      renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, 1.5));
      renderer.setClearColor(0, 0);
      renderer.outputColorSpace = THREE.SRGBColorSpace;
      renderer.toneMapping = THREE.ACESFilmicToneMapping;
      renderer.toneMappingExposure = 1;
      renderer.shadowMap.enabled = true;
      renderer.shadowMap.type = THREE.PCFSoftShadowMap;

      const room = new THREE.RoomEnvironment();
      const environmentGenerator = new THREE.PMREMGenerator(renderer);
      try {
        environment = environmentGenerator.fromScene(room, .04);
        world.environment = environment.texture;
      } finally {
        room.dispose();
        environmentGenerator.dispose();
      }

      const camera = new THREE.PerspectiveCamera(33, 1, .1, 100);
      const target = new THREE.Vector3(0, 1.25, 0);
      const viewDirection = new THREE.Vector3(.19, .13, 1).normalize();
      world.add(new THREE.HemisphereLight('#ffffff', '#bcbcbc', 2));
      const key = new THREE.DirectionalLight('#ffffff', 3);
      key.position.set(-3, 6, 5);
      key.castShadow = true;
      key.shadow.mapSize.set(512, 512);
      Object.assign(key.shadow.camera, { left: -3, right: 3, top: 4, bottom: -2 });
      key.shadow.normalBias = .035;
      world.add(key);
      const fill = new THREE.DirectionalLight('#ffffff', 1.7);
      fill.position.set(3, 3, -3);
      world.add(fill);

      const platformMaterial = new THREE.MeshPhysicalMaterial({
        color: '#dedede', roughness: .56, metalness: .08, clearcoat: .15
      });
      const platform = new THREE.Mesh(new THREE.CylinderGeometry(1.95, 1.97, .065, 80), platformMaterial);
      platform.position.y = -.04;
      platform.receiveShadow = true;
      world.add(platform);
      const shadowMaterial = new THREE.ShadowMaterial({ color: '#000000', opacity: .12 });
      const shadow = new THREE.Mesh(new THREE.CircleGeometry(1.94, 80), shadowMaterial);
      shadow.rotation.x = -Math.PI / 2;
      shadow.position.y = -.002;
      shadow.receiveShadow = true;
      world.add(shadow);

      const receptionist = character.createReceptionist();
      const bell = character.createBell();
      world.add(receptionist, bell);

      function requestDraw() {
        dirty = true;
        schedule();
      }

      function schedule() {
        if (alive && isVisible && hasSize && !raf) raf = requestAnimationFrame(frame);
      }

      function resize() {
        const width = canvas.clientWidth;
        const height = canvas.clientHeight;
        hasSize = width > 0 && height > 0;
        if (!hasSize) {
          cancelAnimationFrame(raf);
          raf = 0;
          lastFrame = null;
          return;
        }
        renderer.setSize(width, height, false);
        camera.aspect = width / height;
        // Fit a sphere enclosing the entire platform, bell and all call poses.
        // Both horizontal and vertical FOV constrain distance in narrow layouts.
        const halfVerticalFov = THREE.MathUtils.degToRad(camera.fov / 2);
        const halfHorizontalFov = Math.atan(Math.tan(halfVerticalFov) * camera.aspect);
        const distance = 2.5 / Math.sin(Math.min(halfVerticalFov, halfHorizontalFov)) * 1.06;
        camera.position.copy(viewDirection).multiplyScalar(distance).add(target);
        camera.far = distance + 20;
        camera.lookAt(target);
        camera.updateProjectionMatrix();
        requestDraw();
      }

      function applyTheme() {
        const dark = document.documentElement.dataset.appearance === 'dark';
        platformMaterial.color.set(dark ? '#333333' : '#dedede');
        shadowMaterial.opacity = dark ? .22 : .12;
        requestDraw();
      }

      motionChanged = function () {
        playbackLevel = 0;
        lastFrame = null;
        requestDraw();
      };

      function isPlaybackPhase() {
        return page !== 'idle' && page !== 'ending' && (phase === 'speaking' || phase === 'greeting');
      }

      function pose(now, delta) {
        const reduced = reducedMotion.matches;
        const effectivePhase = page === 'ending' ? 'ending' : phase;
        const playback = isPlaybackPhase();
        if (!playback || now - playbackSampleAt > 280) playbackTarget = 0;
        playbackLevel += (playbackTarget - playbackLevel) * (1 - Math.exp(-delta * 14));
        if (playbackLevel < .002 && playbackTarget === 0) playbackLevel = 0;
        bell.visible = page === 'idle';
        receptionist.visible = !bell.visible;
        const bellAge = elapsed - bellPressedAt;
        const ringing = bell.visible && bellAge >= 0 && bellAge < .62;
        bell.userData.plunger.position.y = 1.4;
        bell.userData.body.rotation.z = 0;
        if (ringing && !reduced) {
          bell.userData.plunger.position.y -= Math.sin(Math.min(1, bellAge / .35) * Math.PI) * .13;
          bell.userData.body.rotation.z = Math.sin(bellAge * 34) * Math.exp(-bellAge * 10) * .025;
        }
        let moving = ringing;
        if (receptionist.visible) {
          let motion = 'idle';
          let motionTime = 0;
          if (effectivePhase === 'listening') {
            motion = 'waiting';
            motionTime = reduced ? .65 : elapsed * .6;
            moving = true;
          } else if (effectivePhase === 'thinking') {
            motion = 'working';
            motionTime = reduced ? .3 : elapsed;
            moving = true;
          } else if (effectivePhase === 'connecting') {
            motion = 'waiting';
            motionTime = reduced ? .4 : elapsed * .4;
            moving = true;
          }
          character.animateCharacter(receptionist, { motion, time: motionTime });
          const rig = receptionist.userData.rig;
          receptionist.rotation.y = .12;
          if (effectivePhase === 'listening') rig.head.rotation.x -= .06;
          if (effectivePhase === 'ending' || effectivePhase === 'stopped') {
            rig.head.rotation.x = .1;
            rig.leftArm.rotation.z = -.08;
            rig.rightArm.rotation.z = .08;
          }
          // Silence is a still speaking pose. Only fresh output energy adds
          // speech motion; provider phase and microphone activity cannot do so.
          if (playback && !reduced && playbackLevel > 0) {
            rig.head.rotation.x += Math.sin(elapsed * 8) * playbackLevel * .07;
            rig.body.position.y += playbackLevel * .018;
            rig.sprout.rotation.z += Math.sin(elapsed * 6) * playbackLevel * .08;
            moving = true;
          }
          receptionist.updateMatrixWorld(true);
        }
        return !reduced && moving;
      }

      function frame(now) {
        raf = 0;
        if (!alive || !isVisible || !hasSize) return;
        // Invalidations always render, including under reduced motion. Continuous
        // poses are limited to 30fps and never excluded for the active-call page.
        if (!dirty && lastFrame !== null && now - lastFrame < 32) {
          schedule();
          return;
        }
        const delta = lastFrame === null ? 1 / 30 : Math.min(.08, (now - lastFrame) / 1000);
        lastFrame = now;
        elapsed += delta;
        dirty = false;
        const moving = pose(now, delta);
        try {
          renderer.render(world, camera);
        } catch (error) {
          isVisible = false;
          unavailable(error);
          return;
        }
        if (moving) schedule();
        else lastFrame = null;
      }

      resizeObserver = new ResizeObserver(resize);
      resizeObserver.observe(canvas);
      themeObserver = new MutationObserver(applyTheme);
      themeObserver.observe(document.documentElement, { attributes: true, attributeFilter: ['data-appearance'] });
      reducedMotion.addEventListener('change', motionChanged);
      applyTheme();
      resize();

      return {
        setState(nextPage, nextPhase) {
          if (page === nextPage && phase === nextPhase) return;
          page = nextPage;
          phase = nextPhase;
          if (!isPlaybackPhase()) playbackTarget = playbackLevel = 0;
          requestDraw();
        },
        pressBell() {
          if (page !== 'idle') return;
          bellPressedAt = elapsed;
          requestDraw();
        },
        energy(level) {
          if (!isPlaybackPhase() || !isVisible) return;
          playbackTarget = Number.isFinite(level) ? THREE.MathUtils.clamp(level, 0, 1) : 0;
          playbackSampleAt = performance.now();
          if (!reducedMotion.matches) requestDraw();
        },
        setVisible(nextVisible) {
          if (isVisible === nextVisible) return;
          isVisible = nextVisible;
          playbackTarget = playbackLevel = 0;
          lastFrame = null;
          if (isVisible) requestDraw();
          else {
            cancelAnimationFrame(raf);
            raf = 0;
          }
        },
        dispose
      };
    } catch (error) {
      dispose();
      throw error;
    }
  }

  document.addEventListener('visibilitychange', visibilityChanged);
  window.addEventListener('pagehide', pageHide);
  window.addEventListener('pageshow', pageShow);
  // Three installs its own restore handler during mount; register ours after it.
  mount();
  canvas.addEventListener('webglcontextlost', contextWasLost);
  canvas.addEventListener('webglcontextrestored', contextWasRestored);
})();
