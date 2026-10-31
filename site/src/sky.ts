/*
 * The page's background: the orb's sky spread over the whole night, dimmed and drifting slowly, brightest
 * at the top of the window. Drawn at a fraction of the window's size and stretched, since a cloud needs no
 * sharp pixels and the graphics chip little work; at most 30 frames a second, and one still frame for
 * reduced motion. Without WebGL the still glow in style.css stays.
 */

const VERTEX = `
attribute vec2 a_position;
void main() {
  gl_Position = vec4(a_position, 0.0, 1.0);
}
`;

// highp: with mediump the noise's large coordinates lost their fraction and the sky stayed black.
const FRAGMENT = `
precision highp float;
uniform vec2 u_resolution;
uniform float u_time;

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
  float amplitude = 0.5;
  mat2 rotation = mat2(0.80, 0.60, -0.60, 0.80);
  for (int octave = 0; octave < 4; octave++) {
    value += amplitude * noise(p);
    p = rotation * p * 2.0 + vec2(9.7, 4.3);
    amplitude *= 0.5;
  }
  return value;
}

void main() {
  vec2 uv = gl_FragCoord.xy / u_resolution;
  vec2 p = (uv - 0.5) * vec2(u_resolution.x / u_resolution.y, 1.0) * 1.7;
  float t = u_time;

  vec2 warp = vec2(
    fbm(p * 0.9 + vec2(t * 0.030, -t * 0.021)),
    fbm(p * 1.1 + vec2(-t * 0.024, t * 0.027) + vec2(4.7, 1.3))
  );
  float field = fbm(p * 0.8 + (warp - 0.5) * 2.2 + vec2(t * 0.012, 0.0));

  // The orb's colours: violet depth, blue sky, a little of its teal where the cloud is thickest.
  vec3 deep = vec3(0.40, 0.33, 0.95);
  vec3 sky = vec3(0.45, 0.58, 0.98);
  vec3 low = vec3(0.55, 0.86, 0.86);
  vec3 color = mix(deep, sky, smoothstep(0.30, 0.70, warp.x));
  color = mix(color, low, smoothstep(0.58, 0.85, field) * 0.55);

  float cloud = smoothstep(0.28, 0.82, field);
  float top = mix(0.30, 1.0, smoothstep(0.05, 1.0, uv.y));
  float light = cloud * top * 0.23;
  // A still dither against banding in the dark.
  gl_FragColor = vec4(color * light + (hash(gl_FragCoord.xy) - 0.5) / 255.0, 1.0);
}
`;

/** The canvas's pixels per CSS pixel. */
const SCALE = 0.2;

export function startSky(): void {
  const canvas = document.createElement("canvas");
  canvas.className = "sky";
  canvas.setAttribute("aria-hidden", "true");
  const gl = canvas.getContext("webgl", { alpha: false, antialias: false, depth: false, powerPreference: "low-power" });
  if (!gl) return;
  const program = link(gl);
  if (!program) return;

  gl.useProgram(program);
  gl.bindBuffer(gl.ARRAY_BUFFER, gl.createBuffer());
  // One triangle that covers the whole canvas.
  gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([-1, -1, 3, -1, -1, 3]), gl.STATIC_DRAW);
  const position = gl.getAttribLocation(program, "a_position");
  gl.enableVertexAttribArray(position);
  gl.vertexAttribPointer(position, 2, gl.FLOAT, false, 0, 0);
  const resolution = gl.getUniformLocation(program, "u_resolution");
  const time = gl.getUniformLocation(program, "u_time");

  document.body.prepend(canvas);

  const fit = () => {
    const width = Math.max(1, Math.round(canvas.clientWidth * SCALE));
    const height = Math.max(1, Math.round(canvas.clientHeight * SCALE));
    if (canvas.width !== width || canvas.height !== height) {
      canvas.width = width;
      canvas.height = height;
      gl.viewport(0, 0, width, height);
    }
  };
  // A random start, so the sky is not the same on every visit.
  const offset = Math.random() * 200;
  const draw = (now: number) => {
    fit();
    gl.uniform2f(resolution, canvas.width, canvas.height);
    gl.uniform1f(time, offset + now / 1000);
    gl.drawArrays(gl.TRIANGLES, 0, 3);
  };

  const still = window.matchMedia("(prefers-reduced-motion: reduce)");
  let last = -Infinity;
  const frame = (now: number) => {
    if (now - last >= 33) {
      last = now;
      draw(now);
    }
    if (!still.matches) requestAnimationFrame(frame);
  };
  requestAnimationFrame(frame);
  still.addEventListener("change", () => {
    if (!still.matches) requestAnimationFrame(frame);
  });
  window.addEventListener("resize", () => {
    if (still.matches) draw(performance.now());
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
