/* Original procedural Manzanilla robot and reception bell, extracted from the
 * application scene bundle. Geometry, artwork attribution and named rig retained.
 * Editable application source; offline Three.js dependency is vendor/three.js. */
(function () {
  "use strict";
  const { Mesh, CylinderGeometry, MeshPhysicalMaterial, Group, Vector2, Vector3, Color, BufferGeometry, Float32BufferAttribute, SphereGeometry, CapsuleGeometry, TorusGeometry, Shape, ExtrudeGeometry, CatmullRomCurve3, TubeGeometry, RoundedBoxGeometry, MeshStandardMaterial, MathUtils, CircleGeometry, LatheGeometry } = window.Manzanilla3DVendor;
var CHARACTER_ROLES = [{
  id: "default",
  label: "General",
  color: "#F5F5F2"
}, {
  id: "chief",
  label: "Chief of staff",
  color: "#9561ED"
}, {
  id: "assistant",
  label: "Assistant",
  color: "#3299F4"
}, {
  id: "inbox",
  label: "Inbox manager",
  color: "#20BA78"
}, {
  id: "sales",
  label: "Sales",
  color: "#17B4BD"
}, {
  id: "talent",
  label: "Talent scout",
  color: "#A97C60"
}, {
  id: "growth",
  label: "Growth marketer",
  color: "#FF934F"
}, {
  id: "support",
  label: "Customer support",
  color: "#F54E98"
}, {
  id: "expense",
  label: "Expense manager",
  color: "#E65CAA"
}, {
  id: "invoice",
  label: "Invoice collector",
  color: "#7160E9"
}];
var COLOR_ALIASES = {
  white: "#F5F5F2",
  purple: "#9561ED",
  violet: "#9561ED",
  blue: "#3299F4",
  green: "#20BA78",
  teal: "#17B4BD",
  cyan: "#17B4BD",
  brown: "#A97C60",
  orange: "#FF934F",
  coral: "#ED6383",
  red: "#ED6383",
  pink: "#E65CAA",
  indigo: "#7160E9"
};
function resolveCharacterColor(i = "default", e) {
  let t = e?.trim().toLowerCase();
  return t && (/^#[\da-f]{6}$/).test(t) ? t.toUpperCase() : t && (/^#[\da-f]{3}$/).test(t) ? `#${[...t.slice(1)].map(n => n + n).join("")}`.toUpperCase() : t && Object.hasOwn(COLOR_ALIASES, t) ? COLOR_ALIASES[t] : CHARACTER_ROLES.find(n => n.id === i)?.color ?? CHARACTER_ROLES[0].color;
}
function createShellMaterial(i, e = .35) {
  return new MeshPhysicalMaterial({
    color: i,
    roughness: e,
    metalness: .015,
    clearcoat: .38,
    clearcoatRoughness: .29
  });
}
function addMesh(i, e, t, n, s) {
  let r = new Mesh(t, n);
  return (r.name = e, r.position.set(...s), r.castShadow = !0, r.receiveShadow = !0, i.add(r), r);
}
function addRoundedBox(i, e, t, n, s, r) {
  return addMesh(i, e, new RoundedBoxGeometry(...t, 5, n), s, r);
}
function addEllipsoid(i, e, t, n, s) {
  let r = addMesh(i, e, new SphereGeometry(1, 28, 20), n, s);
  return (r.scale.set(...t), r);
}
var SHELL_EXPONENT = .78, SHELL_HALF_WIDTH = .91, SHELL_HALF_HEIGHT = .95, SHELL_HALF_DEPTH = .71, SHELL_CENTER_Y = -.17, signedPower = (i, e) => Math.sign(i) * Math.pow(Math.abs(i), e);
function createHeadShell(i, e, t, n) {
  let s = [], r = [], a = Array.from({
    length: 41
  }, (h, d) => -Math.PI / 2 + d / 40 * Math.PI), o = Math.asin(signedPower((-.51 - SHELL_CENTER_Y) / SHELL_HALF_HEIGHT, 1 / SHELL_EXPONENT));
  (a.push(o), a.sort((h, d) => h - d));
  let c = 64;
  for (let h of a) {
    let d = signedPower(Math.cos(h), SHELL_EXPONENT);
    for (let f = 0; f <= c; f++) {
      let g = f / c * Math.PI * 2;
      s.push(SHELL_HALF_WIDTH * d * signedPower(Math.sin(g), SHELL_EXPONENT), SHELL_CENTER_Y + SHELL_HALF_HEIGHT * signedPower(Math.sin(h), SHELL_EXPONENT), SHELL_HALF_DEPTH * d * signedPower(Math.cos(g), SHELL_EXPONENT));
    }
  }
  let l = new BufferGeometry();
  for (let h = 0; h < a.length - 1; h++) {
    let d = r.length;
    for (let f = 0; f < c; f++) {
      let g = h * (c + 1) + f, _ = g + c + 1;
      r.push(g, g + 1, _, _, g + 1, _ + 1);
    }
    l.addGroup(d, r.length - d, (a[h] + a[h + 1]) / 2 < o ? 1 : 0);
  }
  (l.setAttribute("position", new Float32BufferAttribute(s, 3)), l.setIndex(r), l.computeVertexNormals());
  let u = new Mesh(l, [t, n]);
  return (u.name = e, u.castShadow = u.receiveShadow = !0, i.add(u), u);
}
function shellDepthAt(i, e) {
  let t = 2 / SHELL_EXPONENT, n = 1 - Math.pow(Math.abs(i / SHELL_HALF_WIDTH), t) - Math.pow(Math.abs((e - SHELL_CENTER_Y) / SHELL_HALF_HEIGHT), t);
  return SHELL_HALF_DEPTH * Math.pow(Math.max(.001, n), 1 / t);
}
function createFacePanel(i, e) {
  let s = [0, .045, shellDepthAt(0, .045) + .012], r = [];
  for (let o = 1; o <= 16; o++) for (let c = 0; c < 64; c++) {
    let l = c / 64 * Math.PI * 2, u = Math.cos(l), h = Math.sin(l), d = Math.pow(Math.pow(Math.abs(u), 3.1) + Math.pow(Math.abs(h), 3.1), -1 / 3.1), f = .755 * o / 16 * d * u, g = .045 + .575 * o / 16 * d * h;
    s.push(f, g, shellDepthAt(f, g) + .012);
  }
  for (let o = 0; o < 64; o++) r.push(0, 1 + o, 1 + (o + 1) % 64);
  for (let o = 1; o < 16; o++) for (let c = 0; c < 64; c++) {
    let l = 1 + (o - 1) * 64 + c, u = 1 + (o - 1) * 64 + (c + 1) % 64, h = l + 64, d = u + 64;
    r.push(l, h, u, u, h, d);
  }
  let a = new BufferGeometry();
  return (a.setAttribute("position", new Float32BufferAttribute(s, 3)), a.setIndex(r), a.computeVertexNormals(), addMesh(i, "face", a, e, [0, 0, 0]));
}
function addGroup(i, e, t) {
  let n = new Group();
  return (n.name = e, n.position.set(...t), i.add(n), n);
}
function addTube(i, e, t, n, s = .014) {
  let r = new CatmullRomCurve3(t.map(a => new Vector3(...a)));
  return addMesh(i, e, new TubeGeometry(r, 18, s, 8, !1), n, [0, 0, 0]);
}
function addLeaf(i, e, t, n, s, r = 1) {
  let a = new Shape();
  (a.moveTo(0, 0), a.bezierCurveTo(-.14, .12, -.16, .36, -.035, .49), a.bezierCurveTo(.14, .46, .17, .19, 0, 0));
  let o = addMesh(i, e, new ExtrudeGeometry(a, {
    depth: .025,
    bevelEnabled: !0,
    bevelSize: .035,
    bevelThickness: .035,
    bevelSegments: 4,
    curveSegments: 12,
    steps: 1
  }), t, n);
  return (o.rotation.z = s, o.rotation.y = -.15, o.scale.setScalar(r), o);
}
function addHeadset(i, e, t) {
  let n = addMesh(i, "headset-band", new TorusGeometry(.87, .035, 10, 42, Math.PI), t, [0, -.02, .08]);
  n.scale.y = .98;
  for (let s of [-1, 1]) (addEllipsoid(i, `headset-ear-${s}`, [.12, .235, .19], t, [s * .865, -.09, .18]), addEllipsoid(i, `headset-shell-${s}`, [.06, .175, .145], e, [s * .94, -.09, .19]));
  (addTube(i, "headset-boom", [[.88, -.16, .28], [.79, -.35, .61], [.49, -.39, .77]], t, .026), addEllipsoid(i, "headset-microphone", [.09, .055, .055], t, [.46, -.39, .77]));
}
function addTablet(i, e, t) {
  let n = addGroup(i, "tablet", [-.19, -.17, .77]);
  (n.rotation.set(-.13, -.16, -.04), addRoundedBox(n, "tablet-frame", [.55, .66, .09], .055, e, [0, 0, 0]), addRoundedBox(n, "tablet-screen", [.45, .53, .02], .028, t, [0, .015, .055]));
  let s = createShellMaterial("#BCC9D8", .45);
  for (let r = 0; r < 3; r++) addRoundedBox(n, `tablet-line-${r}`, [r === 2 ? .19 : .31, .023, .011], .008, s, [-.04, .14 - r * .09, .071]);
}
function addDocument(i, e, t, n) {
  let s = addGroup(i, e === "expense" ? "receipt" : "document", [-.13, -.15, .79]);
  if ((s.rotation.set(-.07, -.16, -.08), e === "expense")) {
    let a = new Shape();
    (a.moveTo(-.255, .38), a.lineTo(.255, .38), a.lineTo(.255, -.33));
    for (let o = 0; o < 7; o++) a.lineTo(.255 - o * .085, o % 2 === 0 ? -.37 : -.32);
    (a.lineTo(-.255, .38), addMesh(s, "receipt-paper", new ExtrudeGeometry(a, {
      depth: .035,
      bevelEnabled: !0,
      bevelSize: .013,
      bevelThickness: .012,
      bevelSegments: 2
    }), t, [0, 0, 0]));
  } else addRoundedBox(s, "document-paper", [.54, .7, .055], .034, t, [0, 0, 0]);
  addRoundedBox(s, "document-heading", [.21, .055, .013], .01, n, [-.07, .23, .055]);
  let r = createShellMaterial("#BBC1C9", .5);
  for (let a = 0; a < 4; a++) addRoundedBox(s, `document-line-${a}`, [a === 3 ? .2 : .34, .025, .015], .009, r, [a === 3 ? -.07 : 0, .1 - a * .105, .055]);
}
function addRoleAccessories(i, e, t, n, s) {
  let {head: r, rightArm: a} = e;
  if ((["chief", "talent", "inbox", "growth", "expense", "invoice"].includes(i) && (a.getObjectByName("hand")?.position.set(.12, -.21, .79), addTube(a, "holding-arm", [[.018, -.06, 0], [.03, -.15, .35], [.12, -.21, .72]], n, .095)), i === "chief")) {
    let o = createShellMaterial("#F7BD45", .3), c = addGroup(r, "crown", [0, .8, .06]), l = new Shape();
    (l.moveTo(-.32, 0), l.lineTo(-.38, .34), l.lineTo(-.17, .22), l.lineTo(0, .43), l.lineTo(.17, .22), l.lineTo(.38, .34), l.lineTo(.32, 0), l.closePath(), addMesh(c, "crown-gold", new ExtrudeGeometry(l, {
      depth: .16,
      bevelEnabled: !0,
      bevelSize: .025,
      bevelThickness: .022,
      bevelSegments: 3
    }), o, [0, 0, 0]), addEllipsoid(c, "crown-jewel", [.042, .052, .022], t, [0, .105, .188]), addTablet(a, s, createShellMaterial("#4B586F", .32)));
  } else if (i === "assistant" || i === "support") addHeadset(r, t, s); else if (i === "inbox") {
    let o = addGroup(a, "envelope", [-.14, -.19, .79]);
    (o.rotation.set(-.04, -.15, -.06), addRoundedBox(o, "envelope-paper", [.65, .45, .07], .027, n, [0, 0, 0]));
    let c = createShellMaterial("#96B3A5", .52);
    (addTube(o, "envelope-flap", [[-.29, .185, .045], [0, -.015, .052], [.29, .185, .045]], c, .012), addTube(o, "envelope-fold-left", [[-.29, -.18, .045], [-.11, -.025, .045]], c, .01), addTube(o, "envelope-fold-right", [[.29, -.18, .045], [.11, -.025, .045]], c, .01));
  } else if (i === "sales") {
    let o = addGroup(a, "megaphone", [.03, -.06, .35]);
    o.rotation.y = -.3;
    let c = addMesh(o, "megaphone-horn", new CylinderGeometry(.25, .1, .46, 28, 1, !0), createShellMaterial("#3685C5"), [.12, .05, 0]);
    c.rotation.z = -Math.PI / 2;
    let l = addMesh(o, "megaphone-mouth", new CircleGeometry(.218, 28), s, [.355, .05, 0]);
    l.rotation.y = Math.PI / 2;
    let u = addMesh(o, "megaphone-rim", new TorusGeometry(.24, .032, 10, 32), createShellMaterial("#87C2EE"), [.36, .05, 0]);
    (u.rotation.y = Math.PI / 2, addRoundedBox(o, "megaphone-handle", [.1, .24, .11], .036, s, [-.01, -.14, 0]));
  } else if (i === "talent") {
    for (let o of [-1, 1]) addMesh(r, `glasses-lens-${o}`, new TorusGeometry(.225, .025, 9, 38), s, [o * .33, .02, .766]);
    addTube(r, "glasses-bridge", [[-.1, .04, .765], [0, .07, .77], [.1, .04, .765]], s, .022);
    for (let o of [-1, 1]) addTube(r, `glasses-temple-${o}`, [[o * .55, .03, .76], [o * .77, .05, .65], [o * .87, .04, .25]], s, .023);
    addTablet(a, s, createShellMaterial("#697383", .4));
  } else if (i === "growth") {
    let o = addGroup(a, "growth-chart", [-.14, -.15, .79]);
    (o.rotation.set(-.05, -.15, -.06), addRoundedBox(o, "chart-board", [.59, .56, .06], .04, n, [0, 0, 0]));
    for (let c = 0; c < 3; c++) {
      let l = .13 + c * .105;
      addRoundedBox(o, `chart-bar-${c}`, [.088, l, .035], .018, t, [-.16 + c * .16, -.19 + l / 2, .05]);
    }
  } else (i === "expense" || i === "invoice") && addDocument(a, i, n, t);
}
function createCharacter(i = "default", e) {
  let t = CHARACTER_ROLES.some(R => R.id === i) ? i : "default", n = new Group();
  (n.name = `manzanilla-robot-${t}`, n.userData.role = t, n.userData.artwork = "Original procedural Manzanilla robot");
  let s = createShellMaterial("#fafafa", .33), r = createShellMaterial("#262626", .39), a = createShellMaterial(resolveCharacterColor(t, e), .3), o = t === "default" ? createShellMaterial("#bcbcbc", .43) : a, c = t === "default" ? createShellMaterial("#9b9b9b", .48) : createShellMaterial(new Color(resolveCharacterColor(t, e)).multiplyScalar(.66), .45), l = createShellMaterial("#6d6d6d", .3), u = addGroup(n, "body", [0, 0, 0]), h = addGroup(u, "head", [0, 1.49, 0]);
  (createHeadShell(h, t === "default" ? "face" : "outer-shell", t === "default" ? s : a, o), t !== "default" && createFacePanel(h, s));
  let d = shellDepthAt(.31, .065) + (t === "default" ? .019 : .031), f = addMesh(h, "eye-left", new CapsuleGeometry(.085, .19, 6, 16), r, [-.31, .065, d]), g = addMesh(h, "eye-right", new CapsuleGeometry(.085, .19, 6, 16), r, [.31, .065, d]);
  (f.scale.z = g.scale.z = .48, f.rotation.z = g.rotation.z = .075);
  let _ = addMesh(h, "eye-cheer-left", new TorusGeometry(.1, .033, 9, 22, Math.PI), r, [-.31, .025, d + .015]), m = addMesh(h, "eye-cheer-right", new TorusGeometry(.1, .033, 9, 22, Math.PI), r, [.31, .025, d + .015]), p = addGroup(u, "arm-left", [-.84, 1, .06]), E = addGroup(u, "arm-right", [.84, 1, .06]);
  for (let [R, D] of [[p, -1], [E, 1]]) {
    addEllipsoid(R, "shoulder-joint", [.11, .13, .14], r, [D * .018, -.06, 0]);
    let S = addMesh(R, "hand", new CapsuleGeometry(.155, .2, 6, 18), s, [D * .06, -.2, .065]);
    S.scale.z = .82;
  }
  let b = addGroup(n, "foot-left", [-.37, .205, .09]), v = addGroup(n, "foot-right", [.37, .205, .09]);
  for (let R of [b, v]) (addRoundedBox(R, "ankle-joint", [.29, .11, .34], .045, r, [0, .14, -.015]), addEllipsoid(R, "foot", [.205, .145, .265], t === "support" ? a : s, [0, 0, 0]), addRoundedBox(R, "sole", [.33, .066, .44], .03, c, [0, -.122, -.005]));
  let T = addGroup(h, "sprout", [.42, .7, .035]);
  (t !== "chief" && t !== "support" && (addTube(T, "leaf-stem", [[0, 0, 0], [.015, .14, .005], [.045, .24, 0]], l, .025), addLeaf(T, "leaf-tall", l, [0, .08, 0], -.23, .94), addLeaf(T, "leaf-small", l, [.005, .06, .04], -1.06, .77)), t === "support" ? addEllipsoid(h, "helmet-cap", [.3, .21, .28], a, [0, .79, 0]) : t !== "default" && t !== "chief" && addEllipsoid(h, "helmet-cap", [.34, .17, .3], a, [-.22, .71, -.08]));
  let A = {
    body: u,
    head: h,
    sprout: T,
    leftArm: p,
    rightArm: E,
    leftFoot: b,
    rightFoot: v,
    leftEye: f,
    rightEye: g,
    leftSmile: _,
    rightSmile: m
  };
  return (n.userData.rig = A, addRoleAccessories(t, A, a, s, r), animateCharacter(n, {
    motion: "idle",
    time: 0
  }), n);
}
var smoothStep = i => {
  let e = MathUtils.clamp(i, 0, 1);
  return e * e * (3 - 2 * e);
};
function animateCharacter(i, {motion: e = "idle", time: t, seed: n = 0}) {
  let s = i.userData.rig;
  if (!s) return;
  let r = Number.isFinite(t) ? Math.max(0, t) : 0, a = Number.isFinite(n) ? Math.abs(n % 1) * 5.1 : 0, o = r + a, {body: c, head: l, sprout: u, leftArm: h, rightArm: d, leftFoot: f, rightFoot: g, leftEye: _, rightEye: m, leftSmile: p, rightSmile: E} = s;
  (c.position.set(0, Math.sin(o * 2.7) * .007, 0), c.rotation.set(0, 0, 0), c.scale.set(1, 1, 1), l.position.set(0, 1.49, 0), l.rotation.set(Math.sin(o * 1.45) * .017, Math.sin(o * 1.04) * .045, 0), u.rotation.set(0, 0, Math.sin(o * 2.7) * .026), h.rotation.set(0, 0, -.16), d.rotation.set(0, 0, .16), f.position.set(-.37, .205, .09), g.position.set(.37, .205, .09), f.rotation.set(0, -.06, 0), g.rotation.set(0, .06, 0));
  let b = o % 4.6, v = b > 3.3 && b < 3.46 ? 1 - .9 * Math.sin((b - 3.3) / .16 * Math.PI) : 1;
  if ((_.scale.set(1, v, .48), m.scale.set(1, v, .48), _.visible = m.visible = !0, p.visible = E.visible = !1, p.scale.set(1, 1, 1), E.scale.set(1, 1, 1), e === "blink")) {
    let T = 1 - .94 * Math.sin(MathUtils.clamp(r / .24, 0, 1) * Math.PI);
    _.scale.y = m.scale.y = T;
  } else if (e === "wave") {
    let T = r % 2.2, A = smoothStep(T / .18) * (1 - smoothStep((T - 1.35) / .3));
    (h.rotation.z = -.16 - A * (2.11 + Math.sin(T * 19) * .22), h.rotation.x = -.13 * A, l.rotation.z = -.06 * A, l.rotation.y = -.05 * A, u.rotation.z += Math.sin(T * 16) * A * .045);
  } else if (e === "working") (l.rotation.x = .13 + Math.sin(o * 5.6) * .028, l.rotation.y = -.035 + Math.sin(o * 1.8) * .09, h.rotation.x = -.32 + Math.sin(o * 9) * .12, d.rotation.x = -.18 + Math.sin(o * 9 + 1.3) * .065, h.rotation.z = -.38, c.position.y = .006 + Math.sin(o * 5.6) * .006); else if (e === "waiting") (l.rotation.z = Math.sin(o * 1.7) * .075, l.rotation.y = Math.sin(o * 1.2) * .13, g.rotation.x = Math.max(0, Math.sin(o * 5)) * .12, h.rotation.z -= Math.sin(o * 2.5) * .025); else if (e === "walk") {
    let T = Math.sin(o * 9.5), A = Math.sin(o * 9.5 + Math.PI);
    (f.position.z += T * .12, g.position.z += A * .12, f.position.y += Math.max(0, T) * .075, g.position.y += Math.max(0, A) * .075, f.rotation.x = T * .28, g.rotation.x = A * .28, h.rotation.x = A * .32, d.rotation.x = T * .25, c.position.y = Math.abs(T) * .035, c.rotation.z = T * .028, l.rotation.z = -T * .018, u.rotation.z += T * .05);
  } else if (e === "point") {
    let T = smoothStep(r / .17) * (1 - smoothStep((r - 1) / .4));
    (d.rotation.z += 1.4 * T, d.rotation.x = -.24 * T, l.rotation.y = .19 * T, l.rotation.z = .045 * T);
  } else if (e === "jump") {
    let T = r < .16 ? Math.sin(r / .16 * Math.PI) : 0, A = MathUtils.clamp((r - .16) / .6, 0, 1), R = Math.sin(A * Math.PI), D = r > .76 && r < 1 ? Math.sin((r - .76) / .24 * Math.PI) : 0, S = T * .07 + D * .035;
    (c.position.y = R * .32 - S, c.scale.set(1 + S * .5, 1 - S, 1 + S * .4), f.position.y += R * .28, g.position.y += R * .28, f.rotation.x = -R * .16, g.rotation.x = R * .16, h.rotation.z -= R * 1.95, d.rotation.z += R * 1.85, l.rotation.z = -R * .045, u.rotation.z -= R * .08);
  } else if (e === "laugh") {
    let T = smoothStep(r / .15) * (1 - smoothStep((r - 1.55) / .45));
    (c.rotation.z = Math.sin(r * 15) * .035 * T, c.position.y += Math.abs(Math.sin(r * 14)) * .025 * T, l.rotation.x = -.07 * T, h.rotation.x = d.rotation.x = -.25 * T, T > .3 && (_.visible = m.visible = !1, p.visible = E.visible = !0));
  } else if (e === "sit") {
    let T = smoothStep(r / .35) * (1 - smoothStep((r - 7.5) / .5));
    (c.position.y = -.27 * T, l.rotation.x = .015, f.position.set(-.37 - .08 * T, .205 - .015 * T, .09 + .31 * T), g.position.set(.37 + .08 * T, .205 - .015 * T, .09 + .31 * T), f.rotation.set(-.92 * T, -.06 - .09 * T, 0), g.rotation.set(-.92 * T, .06 + .09 * T, 0), h.rotation.z = -.06, d.rotation.z = .06, h.rotation.x = d.rotation.x = -.12);
  } else if (e === "celebrate" || e === "cheer") {
    let T = r % 1.4, A = smoothStep(r / .16) * (1 - smoothStep((r - 1.05) / .35)), R = Math.max(0, Math.sin(T / 1.4 * Math.PI * 2)), D = Math.max(0, Math.sin((T - .7) / .7 * Math.PI));
    (c.position.y = R * (e === "cheer" ? .025 : .13) * A, c.scale.set(1 + D * .035 * A, 1 - D * .04 * A, 1 + D * .025 * A), h.rotation.z += (-1.92 + Math.sin(o * 14) * .15) * A, d.rotation.z += (1.76 - Math.sin(o * 14) * .15) * A, l.rotation.z = Math.sin(o * 7) * .06 * A, f.position.y += R * (e === "cheer" ? 0 : .09) * A, g.position.y += R * (e === "cheer" ? 0 : .09) * A, u.rotation.z += Math.sin(o * 14) * .07 * A, e === "cheer" && A > .45 && (_.visible = m.visible = !1, p.visible = E.visible = !0, p.scale.y = E.scale.y = .75 + smoothStep((A - .45) / .3) * .25));
  }
  i.updateMatrixWorld(!0);
}
function createReceptionist() {
  const receptionist = createCharacter("default");
  const rig = receptionist.userData.rig;
  const suit = new MeshPhysicalMaterial({
    color: "#323232", roughness: .74, metalness: .02, clearcoat: .06
  });
  const lapel = new MeshStandardMaterial({ color: "#242424", roughness: .68 });
  const shirt = new MeshStandardMaterial({ color: "#fafafa", roughness: .75 });
  const accent = new MeshPhysicalMaterial({
    color: "#777777", roughness: .5, clearcoat: .2
  });
  const face = rig.head.getObjectByName("face");
  if (Array.isArray(face?.material)) face.material[1] = suit;

  function addCostumePanel(name, outline, material, depth = .028) {
    const shape = new Shape();
    outline.forEach(([x, y], index) => {
      if (index) shape.lineTo(x, y);
      else shape.moveTo(x, y);
    });
    shape.closePath();
    const panel = new Mesh(new ExtrudeGeometry(shape, {
      depth, bevelEnabled: true, bevelSize: .012,
      bevelThickness: .007, bevelSegments: 2
    }), material);
    panel.name = name;
    panel.position.z = .627;
    panel.castShadow = true;
    rig.head.add(panel);
    return panel;
  }

  addCostumePanel("white-shirt", [[-.2, -.48], [.2, -.48], [.12, -.9], [-.12, -.9]], shirt);
  addCostumePanel("left-lapel", [[-.4, -.48], [-.21, -.47], [0, -.92], [-.38, -.78], [-.3, -.68]], lapel).position.z = .65;
  addCostumePanel("right-lapel", [[.4, -.48], [.21, -.47], [0, -.92], [.38, -.78], [.3, -.68]], lapel).position.z = .65;
  addCostumePanel("left-collar", [[-.18, -.47], [0, -.51], [-.09, -.65], [-.23, -.52]], shirt).position.z = .679;
  addCostumePanel("right-collar", [[.18, -.47], [0, -.51], [.09, -.65], [.23, -.52]], shirt).position.z = .679;
  addCostumePanel("tie", [[-.036, -.55], [.036, -.55], [.06, -.78], [0, -.85], [-.06, -.78]], accent).position.z = .69;
  const badge = new Mesh(new RoundedBoxGeometry(.19, .08, .025, 2, .015), new MeshStandardMaterial({
    color: "#e8e8e8", metalness: .35, roughness: .36
  }));
  badge.name = "reception-name-badge";
  badge.position.set(.42, -.64, .627);
  rig.head.add(badge);
  const badgeMark = new Mesh(new SphereGeometry(.023, 12, 8), accent);
  badgeMark.position.set(.37, -.64, .65);
  rig.head.add(badgeMark);
  for (const arm of [rig.leftArm, rig.rightArm]) {
    const sleeve = new Mesh(new CapsuleGeometry(.158, .052, 4, 12), suit);
    sleeve.name = "suit-sleeve";
    sleeve.position.set(arm === rig.leftArm ? -.06 : .06, -.11, .047);
    sleeve.scale.z = .82;
    arm.add(sleeve);
  }
  for (const footGroup of [rig.leftFoot, rig.rightFoot]) {
    const foot = footGroup.getObjectByName("foot");
    if (foot) foot.material = suit;
  }
  return receptionist;
}

function createBell() {
  const bell = new Group();
  bell.name = "manzanilla-reception-bell";
  bell.userData.artwork = "Original procedural Manzanilla reception bell";
  const silver = new MeshPhysicalMaterial({
    color: "#e8e8e8", metalness: .86, roughness: .18,
    clearcoat: .75, clearcoatRoughness: .13
  });
  const base = new MeshPhysicalMaterial({
    color: "#353535", metalness: .46, roughness: .29, clearcoat: .75
  });
  const trim = new MeshPhysicalMaterial({
    color: "#cccccc", metalness: .75, roughness: .23
  });
  const body = new Group();
  bell.add(body);
  function addBellMesh(geometry, material, y, parent = body) {
    const mesh = new Mesh(geometry, material);
    mesh.position.y = y;
    mesh.castShadow = mesh.receiveShadow = true;
    parent.add(mesh);
    return mesh;
  }
  addBellMesh(new CylinderGeometry(1.08, 1.16, .14, 64), base, .12);
  addBellMesh(new CylinderGeometry(1.105, 1.105, .06, 64), trim, .225);
  const profile = [[1.03, .245], [1.055, .29], [1.02, .36], [.985, .53],
    [.92, .76], [.81, .98], [.64, 1.15], [.41, 1.28], [.15, 1.33], [.08, 1.34]]
    .map(([radius, y]) => new Vector2(radius, y));
  addBellMesh(new LatheGeometry(profile, 64), silver, 0);
  addBellMesh(new CylinderGeometry(.135, .135, .105, 32), trim, 1.36);
  const plunger = new Group();
  body.add(plunger);
  plunger.position.y = 1.4;
  addBellMesh(new CylinderGeometry(.048, .048, .2, 20), silver, .09, plunger);
  addBellMesh(new SphereGeometry(1, 32, 16), silver, .245, plunger).scale.set(.31, .11, .31);
  const leaf = new Mesh(new SphereGeometry(1, 20, 12), new MeshPhysicalMaterial({
    color: "#737373", roughness: .37, metalness: .12
  }));
  leaf.scale.set(.12, .21, .024);
  leaf.position.set(0, .7, .955);
  leaf.rotation.z = .57;
  body.add(leaf);
  const smallLeaf = leaf.clone();
  smallLeaf.scale.set(.085, .15, .024);
  smallLeaf.position.set(.16, .68, .92);
  smallLeaf.rotation.z = -.8;
  body.add(smallLeaf);
  Object.assign(bell.userData, { body, plunger });
  bell.scale.setScalar(1.18);
  return bell;
}

  window.ManzanillaCharacter = Object.freeze({ createReceptionist, createBell, animateCharacter });
})();
