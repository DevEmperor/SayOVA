#!/usr/bin/env python3
"""Renders Parley's icons from its cloud orb shader: the watch's launcher icon (adaptive foreground in five
densities), the round orb for the tile preview, the pairing page's favicon and touch icon, the README logo
and the Play Store icon. Run from the repository root; needs NumPy and Pillow.

    python3 tools/render_icons.py

The shader is a NumPy port of the one in wear/.../CloudOrbView.kt and pair/src/page/orb.ts (orb-ui's
CloudTheme, MIT), without the round edge: the icon is the orb edge to edge, and where a mask is wider than
the orb (a square store icon) the cloud simply continues.
"""
import os

import numpy as np
from PIL import Image, ImageDraw

# The frame of the drifting cloud the icons show: violet above, a milky band, teal below.
T = 3.0

def fract(x): return x - np.floor(x)

def hash2(px, py):
    px = fract(px * 123.34); py = fract(py * 456.21)
    d = px * (px + 45.32) + py * (py + 45.32)
    px = px + d; py = py + d
    return fract(px * py)

def noise(px, py):
    ix, iy = np.floor(px), np.floor(py)
    fx, fy = px - ix, py - iy
    ux, uy = fx * fx * (3 - 2 * fx), fy * fy * (3 - 2 * fy)
    a = hash2(ix, iy); b = hash2(ix + 1, iy); c = hash2(ix, iy + 1); d = hash2(ix + 1, iy + 1)
    return (a + (b - a) * ux) * (1 - uy) + (c + (d - c) * ux) * uy

def fbm(px, py):
    value = np.zeros_like(px); amp = 0.52
    for _ in range(5):
        value += amp * noise(px, py)
        # mat2(0.80, 0.60, -0.60, 0.80) * p, column-major as in GLSL
        nx = 0.80 * px - 0.60 * py; ny = 0.60 * px + 0.80 * py
        px = nx * 1.92 + 9.7; py = ny * 1.92 + 4.3
        amp *= 0.5
    return value

def smoothstep(e0, e1, x):
    t = np.clip((x - e0) / (e1 - e0), 0, 1); return t * t * (3 - 2 * t)

def mix(a, b, t): return a + (b - a) * t

def render(size, t, activity=0.22, inner=(0.0, 1.0)):
    """size: pixels; inner: the part of the canvas (0..1) that maps to the orb's uv 0..1."""
    lo, hi = inner
    coords = (np.arange(size) + 0.5) / size
    u = (coords - lo) / (hi - lo)
    ux, uy = np.meshgrid(u, u[::-1])          # uv.y grows upwards like gl_FragCoord
    cx, cy = ux - 0.5, uy - 0.5
    px, py = cx * 2, cy * 2
    wx = fbm(px * 1.02 + t * 0.34, py * 1.02 - t * 0.24)
    wy = fbm(px * 1.08 - t * 0.27 + 6.7, py * 1.08 + t * 0.32 + 2.9)
    curlx = np.sin(py * 2.4 + t * 0.68 + wy * 3.2)
    curly = np.cos(px * 2.1 - t * 0.61 + wx * 3.0)
    k = 1.10 + activity * 0.75; c = 0.035 + activity * 0.18
    qx = px + (wx - 0.5) * k + curlx * c; qy = py + (wy - 0.5) * k + curly * c
    broad = fbm(qx * 0.92 + t * 0.14, qy * 0.92 - t * 0.18)
    folded = fbm(qx * 1.66 - t * 0.23 + 5.2, qy * 1.66 + t * 0.19 + 5.2)
    field = mix(broad, folded, 0.3 + activity * 0.30)
    horizon = 0.46 + 0.08 * np.sin((ux + wx * 0.2) * 5.4 + t * 0.42) + 0.16 * (broad - 0.5)
    upper = smoothstep(horizon - 0.12, horizon + 0.08, uy)
    band = np.exp(-((uy - horizon) * (5.2 + activity * 0.8)) ** 2)
    cloud = smoothstep(0.24, 0.79, field)
    deep = np.array([0.40, 0.33, 0.95]); upperSky = np.array([0.45, 0.58, 0.98])
    lowerSky = np.array([0.55, 0.86, 0.86]); milk = np.array([0.90, 0.95, 0.99])
    up = upper[..., None]
    color = mix(lowerSky, upperSky, up)
    upperDepth = (upper * (0.14 + smoothstep(0.42, 0.78, folded) * 0.5))[..., None]
    color = mix(color, deep, upperDepth)
    milkAmount = np.clip(band * (0.38 + cloud * 0.55), 0, 0.82)[..., None]
    color = mix(color, milk, milkAmount)
    lowerMist = ((1 - upper) * smoothstep(0.58, 0.9, broad) * 0.18)[..., None]
    color = mix(color, milk, lowerMist)
    return (np.clip(color, 0, 1) * 255 + 0.5).astype(np.uint8)


def square(px, inner=(18 / 108, 90 / 108), ss=2):
    """A full square of cloud; [inner] of it is the orb's own square (the visible 72 dp of a 108 dp icon)."""
    big = Image.fromarray(render(px * ss, T, inner=inner), "RGB")
    return big.resize((px, px), Image.LANCZOS)


def disc(px, ss=3):
    big = Image.fromarray(render(px * ss, T), "RGB").convert("RGBA")
    mask = Image.new("L", big.size, 0)
    ImageDraw.Draw(mask).ellipse((0, 0, big.width - 1, big.height - 1), fill=255)
    big.putalpha(mask)
    return big.resize((px, px), Image.LANCZOS)


def main():
    for name, scale in (("mdpi", 1), ("hdpi", 1.5), ("xhdpi", 2), ("xxhdpi", 3), ("xxxhdpi", 4)):
        folder = f"wear/src/main/res/drawable-{name}"
        os.makedirs(folder, exist_ok=True)
        # Adaptive icon foreground: opaque, so it fills every mask.
        square(round(108 * scale)).save(f"{folder}/ic_launcher_foreground.png", optimize=True)
        # The orb on its own, round, for places without a mask (tile preview).
        disc(round(48 * scale)).save(f"{folder}/orb_disc.png", optimize=True)
    # Web: round favicon; a square touch icon (iOS rounds the corners itself); a round logo for the README.
    disc(64).save("pair/public/favicon.png", optimize=True)
    square(180, inner=(0, 1)).save("pair/public/apple-touch-icon.png", optimize=True)
    disc(256).save("pair/public/icon.png", optimize=True)
    # Play Store: a 512 px square, Play applies its own mask.
    os.makedirs("fastlane/metadata/android/en-US/images", exist_ok=True)
    square(512, inner=(0.04, 0.96)).save("fastlane/metadata/android/en-US/images/icon.png", optimize=True)


if __name__ == "__main__":
    main()
