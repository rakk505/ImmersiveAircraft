#!/usr/bin/env node

/**
 * Split a flat GLB-derived Blockbench mesh into semantic vehicle parts and add
 * a resource-pack-only VTOL rig. The visible source triangles and UVs are kept
 * byte-for-byte; only their owning elements and the Blockbench outliner change.
 * Small fan meshes are added to give each engine pod an animated moving part.
 */

import crypto from "node:crypto";
import fs from "node:fs";
import path from "node:path";

function usage(message) {
  if (message) console.error(`Error: ${message}\n`);
  console.error(`Usage:
  node rig_bbmodel.mjs \\
    --input flat.bbmodel \\
    --output rigged.bbmodel \\
    --profile militech_av|trauma_atlus`);
  process.exit(2);
}

function parseArgs(argv) {
  const args = {};
  for (let i = 0; i < argv.length; i += 2) {
    const key = argv[i];
    if (!key?.startsWith("--") || argv[i + 1] === undefined) usage(`Invalid argument ${key ?? ""}`);
    args[key.slice(2)] = argv[i + 1];
  }
  for (const key of ["input", "output", "profile"]) {
    if (!args[key]) usage(`Missing --${key}`);
  }
  return args;
}

function uuid(seed) {
  const hex = crypto.createHash("sha1").update(seed).digest("hex").slice(0, 32).split("");
  hex[12] = "4";
  hex[16] = ((Number.parseInt(hex[16], 16) & 3) | 8).toString(16);
  return `${hex.slice(0, 8).join("")}-${hex.slice(8, 12).join("")}-${hex.slice(12, 16).join("")}-${hex.slice(16, 20).join("")}-${hex.slice(20).join("")}`;
}

function round(value) {
  const result = Math.round(value * 1e6) / 1e6;
  return Object.is(result, -0) ? 0 : result;
}

function sideName(x) {
  return x < 0 ? "left" : "right";
}

const PROFILES = {
  militech_av: {
    parts: [
      "hull", "cockpit", "roof", "rear_hull", "landing_gear",
      "left_door", "right_door",
      "left_front_vtol", "right_front_vtol", "left_rear_vtol", "right_rear_vtol",
    ],
    classify([x, y, z]) {
      const ax = Math.abs(x);
      const side = sideName(x);
      if (ax > 1.08 && z < -2.05) return `${side}_rear_vtol`;
      if (ax > 1.02 && z > 1.75 && y < 1.9) return `${side}_front_vtol`;
      if (ax > 1.12 && z > -1.55 && z < 1.45 && y > 0.35 && y < 1.9) return `${side}_door`;
      if (y < 0.34 && ax > 0.55) return "landing_gear";
      if (z > 2.15 && y > 0.55) return "cockpit";
      if (y > 1.78 && z > -2.15 && z < 2.25) return "roof";
      if (z < -1.9) return "rear_hull";
      return "hull";
    },
    pods: [
      {name: "left_front_vtol", pivot: [-1.38, 0.95, 2.55], fan: [-1.38, 1.48, 2.55], radius: 0.34},
      {name: "right_front_vtol", pivot: [1.38, 0.95, 2.55], fan: [1.38, 1.48, 2.55], radius: 0.34},
      {name: "left_rear_vtol", pivot: [-1.58, 1.14, -3.12], fan: [-1.58, 1.82, -3.12], radius: 0.38},
      {name: "right_rear_vtol", pivot: [1.58, 1.14, -3.12], fan: [1.58, 1.82, -3.12], radius: 0.38},
    ],
    pivots: {
      cockpit: [0, 1.15, 3.1],
      roof: [0, 1.82, 0],
      rear_hull: [0, 1.2, -3],
      landing_gear: [0, 0.25, 0],
      left_door: [-1.18, 1.7, 0],
      right_door: [1.18, 1.7, 0],
    },
    fanUv: [3, 3, 11, 11],
    podTilt: "variable.pressing_interpolated_z * -12",
  },
  trauma_atlus: {
    parts: [
      "hull", "cockpit", "roof", "rear_deck", "landing_gear",
      "left_door", "right_door",
      "left_front_vtol", "right_front_vtol", "left_rear_vtol", "right_rear_vtol",
    ],
    classify([x, y, z]) {
      const ax = Math.abs(x);
      const side = sideName(x);
      if (ax > 1.28 && z > 1.2) return `${side}_front_vtol`;
      if (ax > 1.28 && z < -1.45) return `${side}_rear_vtol`;
      if (ax > 1.16 && z > -1.45 && z < 1.2 && y > 0.55 && y < 2.55) return `${side}_door`;
      if (y < 0.58 && ax > 0.7) return "landing_gear";
      if (z > 2.05 && ax < 1.5) return "cockpit";
      if (y > 2.35 && z > -2.15 && z < 2.1) return "roof";
      if (z < -2.15) return "rear_deck";
      return "hull";
    },
    pods: [
      {name: "left_front_vtol", pivot: [-1.78, 1.1, 2.2], fan: [-1.78, 1.82, 2.2], radius: 0.43},
      {name: "right_front_vtol", pivot: [1.78, 1.1, 2.2], fan: [1.78, 1.82, 2.2], radius: 0.43},
      {name: "left_rear_vtol", pivot: [-1.78, 1.25, -2.35], fan: [-1.78, 1.94, -2.35], radius: 0.43},
      {name: "right_rear_vtol", pivot: [1.78, 1.25, -2.35], fan: [1.78, 1.94, -2.35], radius: 0.43},
    ],
    pivots: {
      cockpit: [0, 1.35, 3],
      roof: [0, 2.4, 0],
      rear_deck: [0, 1.6, -2.7],
      landing_gear: [0, 0.35, 0],
      left_door: [-1.28, 2.15, 0],
      right_door: [1.28, 2.15, 0],
    },
    fanUv: [286, 285, 294, 293],
    podTilt: "variable.pressing_interpolated_z * -12",
  },
};

function makeBone(seed, name, originBlocks, children) {
  return {
    name,
    origin: originBlocks.map((value) => round(value * 16)),
    rotation: [0, 0, 0],
    color: 0,
    uuid: uuid(`${seed}:bone:${name}`),
    export: true,
    mirror_uv: false,
    isOpen: true,
    locked: false,
    visibility: true,
    autouv: 0,
    children,
  };
}

function makeMesh(source, seed, name, color, faceEntries) {
  const vertices = {};
  const faces = {};
  for (const [faceId, face] of faceEntries) {
    faces[faceId] = face;
    for (const vertexId of face.vertices) vertices[vertexId] = source.vertices[vertexId];
  }
  return {
    ...source,
    name,
    color,
    origin: [0, 0, 0],
    rotation: [0, 0, 0],
    vertices,
    faces,
    uuid: uuid(`${seed}:mesh:${name}`),
  };
}

function makeFanMesh(seed, name, center, radius, uv) {
  const [cx, cy, cz] = center.map((value) => value * 16);
  const r = radius * 16;
  const halfWidth = Math.max(0.75, r * 0.12);
  const [u0, v0, u1, v1] = uv;
  const elementUuid = uuid(`${seed}:mesh:${name}`);
  const vertices = {
    a0: [round(cx - r), round(cy), round(cz - halfWidth)],
    a1: [round(cx + r), round(cy), round(cz - halfWidth)],
    a2: [round(cx + r), round(cy), round(cz + halfWidth)],
    a3: [round(cx - r), round(cy), round(cz + halfWidth)],
    b0: [round(cx - halfWidth), round(cy + 0.01), round(cz - r)],
    b1: [round(cx + halfWidth), round(cy + 0.01), round(cz - r)],
    b2: [round(cx + halfWidth), round(cy + 0.01), round(cz + r)],
    b3: [round(cx - halfWidth), round(cy + 0.01), round(cz + r)],
  };
  const face = (ids) => ({
    uv: {
      [ids[0]]: [u0, v0],
      [ids[1]]: [u1, v0],
      [ids[2]]: [u1, v1],
      [ids[3]]: [u0, v1],
    },
    vertices: ids,
    texture: 0,
  });
  return {
    name,
    color: 7,
    origin: [0, 0, 0],
    rotation: [0, 0, 0],
    export: true,
    visibility: true,
    locked: false,
    render_order: "default",
    allow_mirror_modeling: true,
    vertices,
    faces: {
      blade_x: face(["a0", "a1", "a2", "a3"]),
      blade_z: face(["b0", "b1", "b2", "b3"]),
    },
    type: "mesh",
    uuid: elementUuid,
  };
}

function makeRotationAnimator(seed, name, expression, axis) {
  const point = {x: "0", y: "0", z: "0"};
  point[axis] = expression;
  return {
    name,
    type: "bone",
    keyframes: [{
      channel: "rotation",
      data_points: [point],
      uuid: uuid(`${seed}:keyframe:${name}`),
      time: 0,
      color: -1,
      interpolation: "linear",
    }],
  };
}

function main() {
  const args = parseArgs(process.argv.slice(2));
  const profile = PROFILES[args.profile];
  if (!profile) usage(`Unknown --profile ${args.profile}`);

  const model = JSON.parse(fs.readFileSync(args.input, "utf8"));
  const meshes = model.elements.filter((element) => element.type === "mesh");
  if (meshes.length !== 1 || model.elements.length !== 1) {
    throw new Error("Rig input must be a single flat mesh produced by glb_to_bbmodel.mjs");
  }

  const source = meshes[0];
  const seed = `cyberpunk_hovercraft:${args.profile}`;
  const assignments = new Map(profile.parts.map((name) => [name, []]));
  for (const entry of Object.entries(source.faces)) {
    const face = entry[1];
    const points = face.vertices.slice(0, 3).map((id) => source.vertices[id]);
    const center = [0, 1, 2].map((axis) => points.reduce((sum, point) => sum + point[axis], 0) / points.length / 16);
    const part = profile.classify(center);
    assignments.get(part).push(entry);
  }

  const elements = [];
  const byName = new Map();
  profile.parts.forEach((name, index) => {
    const entries = assignments.get(name);
    if (!entries.length) return;
    const element = makeMesh(source, seed, name, index % 8, entries);
    elements.push(element);
    byName.set(name, element);
  });

  const podNames = new Set(profile.pods.map((pod) => pod.name));
  const bodyNames = profile.parts.filter((name) => !podNames.has(name));
  const bodyChildren = bodyNames.map((name) => {
    const element = byName.get(name);
    if (!element) return null;
    if (name === "hull") return element.uuid;
    return makeBone(seed, name, profile.pivots[name] ?? [0, 0, 0], [element.uuid]);
  }).filter(Boolean);
  const animators = {};

  for (const pod of profile.pods) {
    const podElement = byName.get(pod.name);
    if (!podElement) continue;
    const fanName = `${pod.name}_fan`;
    const fanElement = makeFanMesh(seed, `${fanName}_blades`, pod.fan, pod.radius, profile.fanUv);
    elements.push(fanElement);
    const fanBone = makeBone(seed, fanName, pod.fan, [fanElement.uuid]);
    const podBone = makeBone(seed, pod.name, pod.pivot, [podElement.uuid, fanBone]);
    bodyChildren.push(podBone);
    animators[podBone.uuid] = makeRotationAnimator(seed, pod.name, profile.podTilt, "x");
    animators[fanBone.uuid] = makeRotationAnimator(seed, fanName, "variable.engine_rotation * 120", "y");
  }

  const outliner = [makeBone(seed, "airframe", [0, 0, 0], bodyChildren)];

  const animation = {
    uuid: uuid(`${seed}:animation:flight`),
    name: "flight",
    loop: "loop",
    override: false,
    length: 0,
    snapping: 24,
    selected: false,
    anim_time_update: "",
    blend_weight: "",
    start_delay: "",
    loop_delay: "",
    animators,
  };

  model.elements = elements;
  model.outliner = outliner;
  model.animations = [animation];
  model.animation_variable_placeholders = [
    "variable.pressing_interpolated_z=1",
    "variable.engine_rotation=1",
  ].join("\n");
  model.name = path.basename(args.output, path.extname(args.output));

  fs.mkdirSync(path.dirname(args.output), {recursive: true});
  fs.writeFileSync(args.output, `${JSON.stringify(model)}\n`);

  const partCounts = Object.fromEntries([...assignments].map(([name, entries]) => [name, entries.length]));
  console.log(JSON.stringify({
    output: args.output,
    profile: args.profile,
    source_faces: Object.keys(source.faces).length,
    output_faces: elements.reduce((sum, element) => sum + Object.keys(element.faces).length, 0),
    elements: elements.length,
    bones: 1 + bodyChildren.filter((child) => typeof child === "object").length + profile.pods.length,
    part_faces: partCounts,
  }, null, 2));
}

main();
