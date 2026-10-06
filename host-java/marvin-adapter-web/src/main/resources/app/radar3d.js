// SPDX-License-Identifier: MIT
// The LD2450 radar in 3D, for Senses > Radar (docs/ui.md, "The radar in 3D"): a desk with the radar standing on it,
// the floor with a ring every metre, the radar's field of view as a translucent wedge, each person it follows as a
// silhouette with a label, their last five seconds as a fading line, and where Marvin's head looks. The mounting
// (upright, flat face up, upright turned 90°) turns the board and its field, and changes where the same numbers put
// a person, so the difference shows. Drawn only when asked (a new frame, a drag, a resize): nothing runs while the
// screen is hidden. three.js r170 is vendored beside this file (three-LICENSE.txt); this module is loaded only when
// the 3D view is first shown.
//
// World frame, centimetres: X towards the right of someone sitting facing the radar (the robot's left, the device
// frame's +X), Y up from the floor, Z straight out of the radar's face (towards that person). The scene's targets
// come as {right, fwd} in the robot's frame (right = the robot's right), so the radar's own x is -right, its y is fwd.

import * as THREE from "./three.module.min.js";
import { OrbitControls } from "./three-orbit-controls.js";
import { describeTarget } from "./sensorviews.js";

const DEG = Math.PI / 180;
const DESK_Y = 74;                         // desk top, cm above the floor
const SCALE = 3;                           // the radar is drawn three times its size: 44 × 15 mm is a speck at 6 m
const BOARD = { w: 4.4 * SCALE, h: 1.5 * SCALE, d: 0.35 * SCALE };
const VERTICAL_HALF_DEG = 35;              // the LD2450's beam, up and down (the host gives the horizontal half angle)
const HEAD_LIFT = 13;                      // Marvin's head sits above the radar (the looks bench's base)
const TRAIL_S = 5;

/** The ways the board can be mounted: how it is turned, and how high its centre sits on the desk (senses.js explains them). */
export const MOUNTS = {
  upright: {
    q: new THREE.Quaternion(),
    lift: BOARD.h / 2,
  },
  flat: {
    q: new THREE.Quaternion().setFromAxisAngle(new THREE.Vector3(1, 0, 0), -90 * DEG),
    lift: BOARD.d / 2,
  },
  side: {
    q: new THREE.Quaternion().setFromAxisAngle(new THREE.Vector3(0, 0, 1), 90 * DEG),
    lift: BOARD.w / 2,
  },
};

/** Where the camera stands for each view: position, what it looks at, field of view. */
export const VIEWS = {
  seat: { pos: [0, 118, 128], target: [0, 84, 0], fov: 62 },        // the eyes of someone sitting at the desk
  behind: { pos: [0, 116, -75], target: [0, 70, 300], fov: 64 },     // just behind the radar: what it faces
  above: { pos: [90, 780, 640], target: [0, 0, 250], fov: 42 },   // high, three quarters, from your side
  side: { pos: [-600, 190, 230], target: [0, 70, 230], fov: 40 },   // from your left
};

function readPalette() {
  const css = getComputedStyle(document.documentElement);
  const v = (n) => css.getPropertyValue(n).trim() || "#888";
  return {
    bg: v("--bg"), surface: v("--surface"), ink: v("--ink"), muted: v("--muted"), line: v("--line"), strong: v("--strong"),
    soft: v("--soft"), field: v("--live"), person: v("--reminder"), night: document.documentElement.dataset.theme === "night",
  };
}

/** The field of view as seen from the radar: a wedge h° to each side and v° up and down, out to r. Local frame: +Z out. */
function fieldGeometry(h, v, r, nh = 28, nv = 12) {
  const dir = (a, e) => [r * Math.cos(e) * Math.sin(a), r * Math.sin(e), r * Math.cos(e) * Math.cos(a)];
  const pos = [0, 0, 0];
  const idx = [];
  const at = (i, j) => 1 + i * (nv + 1) + j;
  for (let i = 0; i <= nh; i++) {
    for (let j = 0; j <= nv; j++) pos.push(...dir((-h + (2 * h * i) / nh) * DEG, (-v + (2 * v * j) / nv) * DEG));
  }
  for (let i = 0; i < nh; i++) {
    for (let j = 0; j < nv; j++) idx.push(at(i, j), at(i + 1, j), at(i + 1, j + 1), at(i, j), at(i + 1, j + 1), at(i, j + 1));
  }
  for (let i = 0; i < nh; i++) idx.push(0, at(i + 1, 0), at(i, 0), 0, at(i, nv), at(i + 1, nv));    // bottom, top
  for (let j = 0; j < nv; j++) idx.push(0, at(0, j), at(0, j + 1), 0, at(nh, j + 1), at(nh, j));     // the two sides
  const g = new THREE.BufferGeometry();
  g.setAttribute("position", new THREE.Float32BufferAttribute(pos, 3));
  g.setIndex(idx);
  return g;
}

/** The wedge's outline: the four corner rays and the far edge. */
function fieldEdges(h, v, r, n = 36) {
  const dir = (a, e) => new THREE.Vector3(r * Math.cos(e) * Math.sin(a), r * Math.sin(e), r * Math.cos(e) * Math.cos(a));
  const pts = [];
  const o = new THREE.Vector3();
  for (const [a, e] of [[-h, -v], [h, -v], [h, v], [-h, v]]) pts.push(o, dir(a * DEG, e * DEG));
  const edge = (f) => { for (let k = 0; k < n; k++) pts.push(f(k / n), f((k + 1) / n)); };
  edge((t) => dir((-h + 2 * h * t) * DEG, -v * DEG));
  edge((t) => dir((-h + 2 * h * t) * DEG, v * DEG));
  edge((t) => dir(-h * DEG, (-v + 2 * v * t) * DEG));
  edge((t) => dir(h * DEG, (-v + 2 * v * t) * DEG));
  return new THREE.BufferGeometry().setFromPoints(pts);
}

/** The plane the radar measures in (its own x and y): a flat fan. */
function measureFan(h, r) {
  const shape = new THREE.Shape();
  shape.moveTo(0, 0);
  shape.absarc(0, 0, r, (90 - h) * DEG, (90 + h) * DEG, false);
  shape.lineTo(0, 0);
  const g = new THREE.ShapeGeometry(shape, 48);
  g.rotateX(Math.PI / 2);                 // the shape's +Y becomes +Z (out of the face), flat in the local XZ plane
  return g;
}

/** The LD2450 as a little board: dark PCB, gold antenna patches on its face, a white plug at the back. */
function radarBoard() {
  const g = new THREE.Group();
  const pcb = new THREE.Mesh(new THREE.BoxGeometry(BOARD.w, BOARD.h, BOARD.d), new THREE.MeshStandardMaterial({ color: "#1f3b2f", roughness: 0.7 }));
  g.add(pcb);
  const gold = new THREE.MeshStandardMaterial({ color: "#d4a640", metalness: 0.6, roughness: 0.35, emissive: "#3a2a08" });
  const patch = new THREE.BoxGeometry(0.42 * SCALE, 0.42 * SCALE, 0.06 * SCALE);
  // two receiving rows and a transmitting column, roughly as on the real board
  for (let i = 0; i < 4; i++) {
    for (const row of [-0.22, 0.22]) {
      const p = new THREE.Mesh(patch, gold);
      p.position.set((-1.25 + i * 0.6) * SCALE, row * SCALE, BOARD.d / 2 + 0.02 * SCALE);
      g.add(p);
    }
  }
  for (const row of [-0.22, 0.22]) {
    const p = new THREE.Mesh(patch, gold);
    p.position.set(1.45 * SCALE, row * SCALE, BOARD.d / 2 + 0.02 * SCALE);
    g.add(p);
  }
  const plug = new THREE.Mesh(new THREE.BoxGeometry(0.9 * SCALE, 0.5 * SCALE, 0.45 * SCALE), new THREE.MeshStandardMaterial({ color: "#f4f2ea", roughness: 0.5 }));
  plug.position.set(-1.4 * SCALE, 0, -BOARD.d / 2 - 0.22 * SCALE);
  g.add(plug);
  return g;
}

function ring(radius, n = 128) {
  const pts = [];
  for (let k = 0; k <= n; k++) pts.push(new THREE.Vector3(radius * Math.sin((k / n) * 2 * Math.PI), 0, radius * Math.cos((k / n) * 2 * Math.PI)));
  return new THREE.BufferGeometry().setFromPoints(pts);
}

/**
 * Builds the view in `stage` (a positioned box: the canvas goes in it, labels in `labels`). Throws when WebGL is not
 * there. `onUserMove` is called when someone drags the view (the camera presets no longer apply).
 */
export function createRadar3D(stage, labels, { onUserMove } = {}) {
  const renderer = new THREE.WebGLRenderer({ antialias: true, alpha: false, powerPreference: "low-power" });
  renderer.setPixelRatio(Math.min(2, window.devicePixelRatio || 1));
  renderer.localClippingEnabled = true;
  const floorCut = [new THREE.Plane(new THREE.Vector3(0, 1, 0), 0)];   // the beam stops at the floor
  stage.prepend(renderer.domElement);
  const scene = new THREE.Scene();
  const camera = new THREE.PerspectiveCamera(40, 16 / 9, 2, 6000);
  const controls = new OrbitControls(camera, renderer.domElement);
  controls.maxPolarAngle = 88 * DEG;
  controls.minDistance = 25;
  controls.maxDistance = 1800;
  controls.screenSpacePanning = true;

  let col = readPalette();
  const mats = {
    floorLine: new THREE.LineBasicMaterial({ transparent: true, opacity: 0.55 }),
    ring: new THREE.LineBasicMaterial({ transparent: true, opacity: 0.9 }),
    desk: new THREE.MeshStandardMaterial({ transparent: true, opacity: 0.55, roughness: 0.9 }),
    leg: new THREE.MeshStandardMaterial({ transparent: true, opacity: 0.4, roughness: 0.9 }),
    field: new THREE.MeshBasicMaterial({ transparent: true, depthWrite: false, side: THREE.DoubleSide, clippingPlanes: floorCut }),
    fieldEdge: new THREE.LineBasicMaterial({ transparent: true, clippingPlanes: floorCut }),
    fan: new THREE.MeshBasicMaterial({ transparent: true, depthWrite: false, side: THREE.DoubleSide, clippingPlanes: floorCut }),
    person: new THREE.MeshStandardMaterial({ transparent: true, roughness: 0.65 }),
    shadow: new THREE.MeshBasicMaterial({ transparent: true, depthWrite: false }),
    trail: new THREE.MeshBasicMaterial({ vertexColors: true, transparent: true, depthWrite: false, side: THREE.DoubleSide }),
    drop: new THREE.LineDashedMaterial({ dashSize: 6, gapSize: 5, transparent: true, opacity: 0.6 }),
    head: new THREE.MeshStandardMaterial({ roughness: 0.5 }),
    goal: new THREE.MeshBasicMaterial({ transparent: true, opacity: 0.45, depthWrite: false }),
  };

  scene.add(new THREE.HemisphereLight("#ffffff", "#808080", 2.4));
  const sun = new THREE.DirectionalLight("#ffffff", 1.6);
  sun.position.set(-200, 500, 400);
  scene.add(sun);

  // the floor: a grid every 50 cm and a ring every metre around the radar
  const grid = new THREE.Group();
  const gp = [];
  for (let k = -14; k <= 14; k++) {
    gp.push(new THREE.Vector3(k * 50, 0, -700), new THREE.Vector3(k * 50, 0, 700), new THREE.Vector3(-700, 0, k * 50), new THREE.Vector3(700, 0, k * 50));
  }
  grid.add(new THREE.LineSegments(new THREE.BufferGeometry().setFromPoints(gp), mats.floorLine));
  for (let m = 1; m <= 6; m++) grid.add(new THREE.Line(ring(m * 100), mats.ring));
  scene.add(grid);

  // the desk, the radar near its back edge facing you
  const desk = new THREE.Group();
  const top = new THREE.Mesh(new THREE.BoxGeometry(140, 3, 70), mats.desk);
  top.position.set(0, DESK_Y - 1.5, 18);
  desk.add(top);
  for (const [x, z] of [[-66, -14], [66, -14], [-66, 50], [66, 50]]) {
    const leg = new THREE.Mesh(new THREE.BoxGeometry(3, DESK_Y - 3, 3), mats.leg);
    leg.position.set(x, (DESK_Y - 3) / 2, z);
    desk.add(leg);
  }
  scene.add(desk);

  const mount = new THREE.Group();          // the radar and its field, turned by the mounting
  scene.add(mount);
  const board = radarBoard();
  mount.add(board);
  let field = null;
  let fieldKey = "";

  const head = new THREE.Group();            // Marvin's head: a ball and a cone pointing where it looks
  const ball = new THREE.Mesh(new THREE.SphereGeometry(3.4, 20, 14), mats.head);
  const cone = new THREE.Mesh(new THREE.ConeGeometry(2.6, 11, 18), mats.head);
  cone.rotation.x = Math.PI / 2;
  cone.position.z = 9;
  const look = new THREE.Group();
  look.add(cone);
  const goal = new THREE.Group();            // where the nearest target is, faint
  const goalShaft = new THREE.Mesh(new THREE.CylinderGeometry(0.7, 0.7, 34, 8), mats.goal);
  goalShaft.rotation.x = Math.PI / 2;
  goalShaft.position.z = 22;
  const goalTip = new THREE.Mesh(new THREE.ConeGeometry(2.2, 7, 14), mats.goal);
  goalTip.rotation.x = Math.PI / 2;
  goalTip.position.z = 42;
  goal.add(goalShaft, goalTip);
  head.add(ball, look, goal);
  head.visible = false;
  scene.add(head);

  // people: a capsule and a head standing on the floor, or a ball where the numbers put them
  const people = [];
  const shadowGeo = new THREE.CircleGeometry(24, 32).rotateX(-Math.PI / 2);
  for (let i = 0; i < 3; i++) {
    const g = new THREE.Group();
    const body = new THREE.Mesh(new THREE.CapsuleGeometry(16, 78, 6, 16), mats.person);
    body.position.y = 16 + 39;
    const skull = new THREE.Mesh(new THREE.SphereGeometry(11, 20, 14), mats.person);
    skull.position.y = 126;
    const standing = new THREE.Group();
    standing.add(body, skull);
    const shadow = new THREE.Mesh(shadowGeo, mats.shadow);
    shadow.position.y = 0.4;
    const blob = new THREE.Mesh(new THREE.SphereGeometry(9, 20, 14), mats.person);
    const drop = new THREE.Line(new THREE.BufferGeometry().setFromPoints([new THREE.Vector3(), new THREE.Vector3(0, -1, 0)]), mats.drop);
    g.add(standing, shadow, blob, drop);
    g.visible = false;
    scene.add(g);
    people.push({ g, standing, shadow, blob, drop, label: null });
  }
  const trail = new THREE.Mesh(new THREE.BufferGeometry(), mats.trail);
  trail.renderOrder = 1;
  scene.add(trail);

  // labels over the canvas
  const labelPool = [];
  const label = (cls) => {
    const d = document.createElement("div");
    d.className = `radar-label ${cls}`;
    labels.append(d);
    labelPool.push(d);
    return d;
  };
  const ringLabels = [];
  for (let m = 1; m <= 6; m++) {
    const d = label("ring");
    d.textContent = `${m} m`;
    ringLabels.push({ d, at: new THREE.Vector3(m * 100 * Math.sin(-28 * DEG), 0, m * 100 * Math.cos(-28 * DEG)) });
  }
  const radarTag = label("tag");
  radarTag.textContent = "radar";
  const headTag = label("tag");
  headTag.textContent = "Marvin’s head";
  for (const p of people) p.label = label("person");

  const state = { mount: "upright", view: "above", sceneData: null, half: 60, range: 600, anim: 0, size: [0, 0] };

  function applyTheme() {
    col = readPalette();
    renderer.setClearColor(col.bg, 1);
    mats.floorLine.color.set(col.line); mats.floorLine.opacity = col.night ? 0.45 : 0.6;
    mats.ring.color.set(col.strong);
    mats.desk.color.set(col.night ? col.strong : col.line);
    mats.desk.opacity = col.night ? 0.32 : 0.6;
    mats.leg.color.set(col.night ? col.strong : col.line);
    mats.field.color.set(col.field); mats.field.opacity = col.night ? 0.035 : 0.04;
    mats.fieldEdge.color.set(col.field); mats.fieldEdge.opacity = col.night ? 0.6 : 0.7;
    mats.fan.color.set(col.field); mats.fan.opacity = col.night ? 0.16 : 0.2;
    mats.person.color.set(col.person);
    mats.shadow.color.set(col.ink); mats.shadow.opacity = col.night ? 0.25 : 0.12;
    mats.drop.color.set(col.person);
    mats.head.color.set(col.ink);
    mats.goal.color.set(col.person);
    request();
  }

  function buildField() {
    const key = `${state.half}/${state.range}`;
    if (key === fieldKey) return;
    fieldKey = key;
    if (field) { mount.remove(field); field.traverse((o) => o.geometry && o.geometry.dispose()); }
    field = new THREE.Group();
    const fill = new THREE.Mesh(fieldGeometry(state.half, VERTICAL_HALF_DEG, state.range), mats.field);
    const edges = new THREE.LineSegments(fieldEdges(state.half, VERTICAL_HALF_DEG, state.range), mats.fieldEdge);
    const fan = new THREE.Mesh(measureFan(state.half, state.range), mats.fan);
    fill.renderOrder = 2;
    fan.renderOrder = 3;
    field.add(fill, edges, fan);
    mount.add(field);
  }

  function radarOrigin() {
    return new THREE.Vector3(0, DESK_Y + MOUNTS[state.mount].lift, 0);
  }

  /** Where the radar's own (x, y) lands in the world for the current mounting. */
  function place(x, y) {
    return new THREE.Vector3(x, 0, y).applyQuaternion(MOUNTS[state.mount].q).add(radarOrigin());
  }

  function applyMount() {
    mount.quaternion.copy(MOUNTS[state.mount].q);
    mount.position.copy(radarOrigin());
    head.position.set(0, DESK_Y + Math.max(BOARD.h, BOARD.w * (state.mount === "side" ? 1 : 0)) + HEAD_LIFT, 0);
  }

  function updateTrail(points) {
    // the trail comes as {age, right, fwd} without identities: join each point to the nearest one of the frame before
    const byAge = new Map();
    for (const [age, r, f] of points) {
      if (!byAge.has(age)) byAge.set(age, []);
      byAge.get(age).push([age, -r, f]);
    }
    const ages = [...byAge.keys()].sort((a, b) => b - a);
    const pos = [], colors = [];
    const bg = new THREE.Color(col.bg), fg = new THREE.Color(col.person);
    const upright = state.mount === "upright";
    const width = 2.6;
    const tmp = new THREE.Color();
    for (let k = 1; k < ages.length; k++) {
      for (const [age, x, y] of byAge.get(ages[k])) {
        let best = null, bd = 70;
        for (const p of byAge.get(ages[k - 1])) {
          const d = Math.hypot(p[1] - x, p[2] - y);
          if (d < bd) { bd = d; best = p; }
        }
        if (!best) continue;
        const a = place(best[1], best[2]), b = place(x, y);
        if (upright) { a.y = 0.6; b.y = 0.6; }
        const dir = new THREE.Vector3().subVectors(b, a);
        if (dir.lengthSq() < 0.01) continue;
        const side = upright ? new THREE.Vector3(-dir.z, 0, dir.x) : new THREE.Vector3().crossVectors(dir, camera.getWorldDirection(new THREE.Vector3()));
        side.normalize().multiplyScalar(width / 2);
        const ta = Math.max(0, 1 - best[0] / TRAIL_S) * 0.9, tb = Math.max(0, 1 - age / TRAIL_S) * 0.9;
        const ca = tmp.copy(bg).lerp(fg, ta).toArray(), cb = new THREE.Color().copy(bg).lerp(fg, tb).toArray();
        const q = [a.clone().add(side), a.clone().sub(side), b.clone().add(side), b.clone().sub(side)];
        pos.push(...q[0].toArray(), ...q[1].toArray(), ...q[2].toArray(), ...q[1].toArray(), ...q[3].toArray(), ...q[2].toArray());
        colors.push(...ca, ...ca, ...cb, ...ca, ...cb, ...cb);
      }
    }
    trail.geometry.dispose();
    const g = new THREE.BufferGeometry();
    g.setAttribute("position", new THREE.Float32BufferAttribute(pos, 3));
    g.setAttribute("color", new THREE.Float32BufferAttribute(colors, 3));
    trail.geometry = g;
  }

  function updatePeople() {
    const s = state.sceneData;
    const targets = (s && s.targets) || [];
    const upright = state.mount === "upright";
    people.forEach((p, i) => {
      const t = targets[i];
      p.g.visible = !!t;
      p.label.hidden = !t;
      if (!t) return;
      const [right, fwd, speed] = t;
      const x = -right, y = fwd;
      const at = place(x, y);
      p.standing.visible = upright;
      p.shadow.visible = upright;
      p.blob.visible = !upright;
      p.drop.visible = !upright && at.y > 2;
      if (upright) {
        p.g.position.set(at.x, 0, at.z);
        p.labelAt = new THREE.Vector3(at.x, 160, at.z);
      } else {
        p.g.position.copy(at);
        p.drop.geometry.setFromPoints([new THREE.Vector3(), new THREE.Vector3(0, -at.y, 0)]);
        p.drop.computeLineDistances();
        p.labelAt = at.clone().add(new THREE.Vector3(0, 16, 0));
      }
      const { dist, dir } = describeTarget(x, y, state.mount);
      const v = speed == null ? "" : Math.abs(speed) < 1 ? " · still" : ` · ${Math.abs(speed)} cm/s`;
      p.label.replaceChildren();
      const n = document.createElement("b");
      n.textContent = String(i + 1);
      p.label.append(n, `${dist} · ${dir}${v}`);
    });
    updateTrail((s && s.trail) || []);
    const h = s && s.head;
    head.visible = !!h;
    headTag.hidden = !h;
    if (h) {
      look.rotation.y = h.angle_deg * DEG;            // positive: towards +X, your right
      goal.visible = h.target_deg != null;
      if (h.target_deg != null) goal.rotation.y = h.target_deg * DEG;
    }
  }

  const v3 = new THREE.Vector3();
  /** Puts a label over a world point, `dx`, `dy` pixels off; a person's label stays inside the stage. */
  function placeLabel(d, at, dx = 0, dy = 0, keepIn = false) {
    if (d.hidden) return;
    v3.copy(at).project(camera);
    const [w, h] = state.size;
    if (v3.z > 1 || v3.z < -1 || Math.abs(v3.x) > 1.2 || Math.abs(v3.y) > 1.2) { d.style.visibility = "hidden"; return; }
    d.style.visibility = "";
    let x = (v3.x * 0.5 + 0.5) * w + dx, y = (-v3.y * 0.5 + 0.5) * h + dy;
    if (keepIn) {
      const lw = d.offsetWidth, lh = d.offsetHeight;
      x = Math.max(lw / 2 + 6, Math.min(w - lw / 2 - 6, x));
      y = Math.max(lh + 46, Math.min(h - 6, y));            // below the view's note
    }
    d.style.transform = `translate(${Math.round(x)}px, ${Math.round(y)}px) translate(-50%, -100%)`;
  }

  function render() {
    const r = stage.getBoundingClientRect();
    if (!r.width || !r.height) return;
    if (r.width !== state.size[0] || r.height !== state.size[1]) {
      state.size = [r.width, r.height];
      renderer.setSize(r.width, r.height, false);
      camera.aspect = r.width / r.height;
      camera.updateProjectionMatrix();
    }
    // a person between the camera and the radar (you, seen from your seat) fades so the radar stays visible
    const near = people.some((p) => p.g.visible && p.g.position.distanceTo(camera.position) < 90);
    mats.person.opacity = near ? 0.35 : 0.88;
    renderer.render(scene, camera);
    for (const { d, at } of ringLabels) placeLabel(d, at);
    placeLabel(radarTag, new THREE.Vector3(0, DESK_Y, 0), 0, 18);
    if (head.visible) placeLabel(headTag, head.position, 0, -10);
    for (const p of people) if (p.g.visible) placeLabel(p.label, p.labelAt, 0, 0, true);
  }

  let pending = 0;
  function request() {
    if (pending) return;
    pending = requestAnimationFrame(() => { pending = 0; render(); });
  }

  function setView(name, animate = true) {
    const v = VIEWS[name] || VIEWS.above;
    state.view = name;
    const to = { pos: new THREE.Vector3(...v.pos), target: new THREE.Vector3(...v.target), fov: v.fov };
    cancelAnimationFrame(state.anim);
    const reduce = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
    if (!animate || reduce) {
      camera.position.copy(to.pos);
      controls.target.copy(to.target);
      camera.fov = to.fov;
      camera.updateProjectionMatrix();
      controls.update();
      request();
      return;
    }
    const from = { pos: camera.position.clone(), target: controls.target.clone(), fov: camera.fov };
    const t0 = performance.now();
    const step = (now) => {
      const t = Math.min(1, (now - t0) / 650);
      const e = t < 0.5 ? 2 * t * t : 1 - (-2 * t + 2) ** 2 / 2;
      camera.position.lerpVectors(from.pos, to.pos, e);
      controls.target.lerpVectors(from.target, to.target, e);
      camera.fov = from.fov + (to.fov - from.fov) * e;
      camera.updateProjectionMatrix();
      controls.update();
      render();
      if (t < 1) state.anim = requestAnimationFrame(step);
    };
    state.anim = requestAnimationFrame(step);
  }

  controls.addEventListener("change", request);
  controls.addEventListener("start", () => { cancelAnimationFrame(state.anim); if (onUserMove) onUserMove(); });
  const ro = new ResizeObserver(request);
  ro.observe(stage);

  applyTheme();
  applyMount();
  buildField();
  setView("above", false);

  return {
    /** A new scene from the host (targets, trail, radar, head). */
    update(sceneData) {
      state.sceneData = sceneData;
      const radar = sceneData && sceneData.radar;
      if (radar) { state.half = radar.half_angle_deg || 60; state.range = radar.range_cm || 600; }
      buildField();
      updatePeople();
      request();
    },
    setMount(name) {
      if (!MOUNTS[name] || name === state.mount) return;
      state.mount = name;
      applyMount();
      updatePeople();
      request();
    },
    setView,
    theme: applyTheme,
    render: request,
  };
}
