/*
 * The cloud orb at the top of the page, alive like the one on the watch: the same shader (orb-ui's
 * MIT-licensed CloudTheme in SayOVA's colours, see wear/…/CloudOrbView.kt) drawn with WebGL. Without
 * WebGL the still frame in style.css stays; with reduced motion one frame is drawn and kept.
 */

const VERTEX = `
attribute vec2 a_position;
void main() {
  gl_Position = vec4(a_position, 0.0, 1.0);
}
`;

const FRAGMENT = `
precision highp float;
uniform vec2 u_resolution;
uniform float u_time;
uniform float u_activity;

float hash(vec2 p) {
  p = fract(p * vec2(123.34, 456.21));
  p += dot(p, p + 45.32);
  return fract(p.x * p.y);
}

float noise(vec2 p) {
  vec2 i = floor(p);
  vec2 f = fract(p);
  vec2 u = f * f * (3.0 - 2.0 * f);
  return mix(
    mix(hash(i), hash(i + vec2(1.0, 0.0)), u.x),
    mix(hash(i + vec2(0.0, 1.0)), hash(i + vec2(1.0, 1.0)), u.x),
    u.y
  );
}

float fbm(vec2 p) {
  float value = 0.0;
  float amplitude = 0.52;
  mat2 rotation = mat2(0.80, 0.60, -0.60, 0.80);
  for (int octave = 0; octave < 5; octave++) {
    value += amplitude * noise(p);
    p = rotation * p * 1.92 + vec2(9.7, 4.3);
    amplitude *= 0.5;
  }
  return value;
}

void main() {
  vec2 uv = gl_FragCoord.xy / u_resolution;
  vec2 centered = uv - 0.5;
  float radius = length(centered);
  float edge = 1.0 - smoothstep(0.488, 0.5, radius);

  vec2 p = centered * 2.0;
  float t = u_time;
  vec2 warp = vec2(
    fbm(p * 1.02 + vec2(t * 0.34, -t * 0.24)),
    fbm(p * 1.08 + vec2(-t * 0.27, t * 0.32) + vec2(6.7, 2.9))
  );
  vec2 curl = vec2(
    sin(p.y * 2.4 + t * 0.68 + warp.y * 3.2),
    cos(p.x * 2.1 - t * 0.61 + warp.x * 3.0)
  );
  vec2 warped = p + (warp - 0.5) * (1.10 + u_activity * 0.75) + curl * (0.035 + u_activity * 0.18);
  float broad = fbm(warped * 0.92 + vec2(t * 0.14, -t * 0.18));
  float folded = fbm(warped * 1.66 + vec2(-t * 0.23, t * 0.19) + 5.2);
  float field = mix(broad, folded, 0.3 + u_activity * 0.30);

  float horizon = 0.46 + 0.08 * sin((uv.x + warp.x * 0.2) * 5.4 + t * 0.42) + 0.16 * (broad - 0.5);
  float upper = smoothstep(horizon - 0.12, horizon + 0.08, uv.y);
  float band = exp(-pow((uv.y - horizon) * (5.2 + u_activity * 0.8), 2.0));
  float cloud = smoothstep(0.24, 0.79, field);

  vec3 deep = vec3(0.40, 0.33, 0.95);
  vec3 upperSky = vec3(0.45, 0.58, 0.98);
  vec3 lowerSky = vec3(0.55, 0.86, 0.86);
  vec3 milk = vec3(0.90, 0.95, 0.99);

  vec3 color = mix(lowerSky, upperSky, upper);
  float upperDepth = upper * (0.14 + smoothstep(0.42, 0.78, folded) * 0.5);
  color = mix(color, deep, upperDepth);
  float milkAmount = clamp(band * (0.38 + cloud * 0.55), 0.0, 0.82);
  color = mix(color, milk, milkAmount);
  float lowerMist = (1.0 - upper) * smoothstep(0.58, 0.9, broad) * 0.18;
  color = mix(color, milk, lowerMist);
  color += (noise(gl_FragCoord.xy * 0.64) - 0.5) / 255.0;

  gl_FragColor = vec4(color * edge, edge);
}
`;

/** How fast the cloud drifts: between the watch's idle and listening speeds, calm but visibly alive. */
const FLOW_SPEED = 0.42;

/**
 * Draws the orb into [host]. [level], if given, is a voice's loudness from 0 to 1 (the website's example
 * conversation); the cloud stirs with it, like the watch's orb while someone talks.
 */
export function startOrb(host: HTMLElement, level?: () => number): void {
  const canvas = document.createElement("canvas");
  const gl = canvas.getContext("webgl", { alpha: true, premultipliedAlpha: true, antialias: false });
  if (!gl) return;
  const program = link(gl);
  if (!program) return;

  gl.useProgram(program);
  const buffer = gl.createBuffer();
  gl.bindBuffer(gl.ARRAY_BUFFER, buffer);
  // One triangle that covers the whole canvas.
  gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([-1, -1, 3, -1, -1, 3]), gl.STATIC_DRAW);
  const position = gl.getAttribLocation(program, "a_position");
  gl.enableVertexAttribArray(position);
  gl.vertexAttribPointer(position, 2, gl.FLOAT, false, 0, 0);
  const resolution = gl.getUniformLocation(program, "u_resolution");
  const time = gl.getUniformLocation(program, "u_time");
  const activity = gl.getUniformLocation(program, "u_activity");

  host.append(canvas);
  host.classList.add("live");

  const fit = () => {
    const size = Math.round(host.clientWidth * Math.min(window.devicePixelRatio || 1, 3));
    if (canvas.width !== size) {
      canvas.width = size;
      canvas.height = size;
      gl.viewport(0, 0, size, size);
    }
  };
  // A random start, so the cloud is not the same shape on every visit.
  const offset = Math.random() * 100;
  const draw = (now: number) => {
    fit();
    const t = offset + (now / 1000) * FLOW_SPEED;
    gl.uniform2f(resolution, canvas.width, canvas.height);
    gl.uniform1f(time, t);
    // Breathes slowly, like the watch's orb between words.
    gl.uniform1f(activity, 0.22 + 0.1 * Math.sin(t * 1.7) + 0.7 * (level?.() ?? 0));
    gl.drawArrays(gl.TRIANGLES, 0, 3);
  };

  const still = window.matchMedia("(prefers-reduced-motion: reduce)");
  const frame = (now: number) => {
    draw(now);
    if (!still.matches) requestAnimationFrame(frame);
  };
  requestAnimationFrame(frame);
  still.addEventListener("change", () => {
    if (!still.matches) requestAnimationFrame(frame);
  });
}

function link(gl: WebGLRenderingContext): WebGLProgram | null {
  const compile = (type: number, source: string) => {
    const shader = gl.createShader(type);
    if (!shader) return null;
    gl.shaderSource(shader, source);
    gl.compileShader(shader);
    return gl.getShaderParameter(shader, gl.COMPILE_STATUS) ? shader : null;
  };
  const vertex = compile(gl.VERTEX_SHADER, VERTEX);
  const fragment = compile(gl.FRAGMENT_SHADER, FRAGMENT);
  const program = gl.createProgram();
  if (!vertex || !fragment || !program) return null;
  gl.attachShader(program, vertex);
  gl.attachShader(program, fragment);
  gl.linkProgram(program);
  return gl.getProgramParameter(program, gl.LINK_STATUS) ? program : null;
}
