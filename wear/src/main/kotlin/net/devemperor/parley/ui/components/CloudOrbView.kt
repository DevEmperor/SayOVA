/*
 * Copyright (C) 2026 DevEmperor (Parley)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * The cloud shader and its visual mapping are adapted from orb-ui's CloudTheme:
 * Copyright (c) 2026 Alexander Chen, licensed under the MIT License.
 * https://github.com/exprmntl/orb-ui (formerly alexanderqchen/orb-ui), src/themes/cloud/CloudTheme.tsx
 * The Android port follows Dictate's AudioReactiveCloudOrbView by the same author as Parley.
 */

package net.devemperor.parley.ui.components

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import android.view.View
import androidx.annotation.RequiresApi
import androidx.core.graphics.withRotation
import androidx.core.graphics.withScale
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

/**
 * Parley's orb: orb-ui's Cloud theme, recoloured from its periwinkle sky to Parley's violet-over-teal,
 * and extended with the states of a conversation.
 *
 * - [Mode.IDLE]: a slowly drifting cloud on the start screens.
 * - [Mode.LISTENING]: the cloud churns, brightens and grows a little with the microphone level.
 * - [Mode.SPEAKING]: the same, driven by Parley's own voice, flowing the other way round.
 * - [Mode.THINKING]: a calm flow with a small spinner (also used while connecting).
 * - [Mode.DONE] / [Mode.ERROR]: the whole field tints teal / coral, so the outcome still reads as the
 *   same cloud.
 *
 * Android 13+ renders the field as an AGSL [RuntimeShader]; older watches, and any frame on a software
 * canvas, get the same palette and motion from cached gradients.
 */
class CloudOrbView(context: Context) : View(context) {

    enum class Mode { IDLE, LISTENING, SPEAKING, THINKING, DONE, ERROR }

    private var mode = Mode.IDLE
    private var targetLevel = 0f
    private var currentLevel = 0f
    private var currentScale = IDLE_SCALE
    private var flowTime = 0f
    private var currentTint = 0f
    private var tint = DONE_TINT
    private var lastFrameNanos = 0L
    private var paused = false

    private val cloudRenderer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        runCatching { RuntimeCloudRenderer() }.getOrNull()
    } else {
        null
    }

    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fallbackBasePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fallbackMistPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fallbackTintPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val spinnerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = SPINNER
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun setMode(value: Mode) {
        if (mode == value) return
        mode = value
        lastFrameNanos = 0L
        when (value) {
            Mode.DONE -> tint = DONE_TINT
            Mode.ERROR -> tint = ERROR_TINT
            Mode.IDLE -> currentLevel = 0f
            else -> Unit
        }
        invalidate()
    }

    fun setLevel(value: Float) {
        targetLevel = value.coerceIn(0f, 1f)
    }

    /** Muted: the cloud dims and stops reacting. */
    fun setPaused(value: Boolean) {
        if (paused == value) return
        paused = value
        alpha = if (value) PAUSED_ALPHA else 1f
        lastFrameNanos = 0L
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        if (w <= 0 || h <= 0) return
        val radius = min(w, h) / 2f
        haloPaint.shader = RadialGradient(
            w / 2f, h / 2f, radius,
            intArrayOf(HALO, HALO, Color.TRANSPARENT),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP,
        )
        fallbackBasePaint.shader = LinearGradient(
            0f, 0f, 0f, h.toFloat(),
            intArrayOf(DEEP, UPPER, MILK, LOWER),
            floatArrayOf(0f, 0.34f, 0.55f, 1f),
            Shader.TileMode.CLAMP,
        )
        fallbackMistPaint.shader = RadialGradient(
            w * 0.42f, h * 0.53f, min(w, h) * 0.48f,
            intArrayOf(0xDDE6F2FC.toInt(), 0x70E6F2FC, Color.TRANSPARENT),
            floatArrayOf(0f, 0.48f, 1f),
            Shader.TileMode.CLAMP,
        )
        spinnerPaint.strokeWidth = min(w, h) * 0.035f
    }

    override fun onDraw(canvas: Canvas) {
        if (width == 0 || height == 0) return
        val now = System.nanoTime()
        val deltaSeconds = if (lastFrameNanos == 0L) 0f else min((now - lastFrameNanos) / 1_000_000_000f, MAX_DELTA_SECONDS)
        lastFrameNanos = now

        val motionEnabled = shouldAnimate()
        updateMotion(deltaSeconds, motionEnabled)

        // The AGSL field only exists on the GPU; a software canvas would throw. Ask the canvas per frame.
        val renderer = cloudRenderer
        val useShader = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && canvas.isHardwareAccelerated && renderer != null

        val cx = width / 2f
        val cy = height / 2f
        // A soft halo that swells with the voice, so the orb glows into the black around it.
        haloPaint.alpha = (70 + 120 * currentLevel).toInt().coerceIn(0, 255)
        canvas.drawCircle(cx, cy, min(width, height) / 2f, haloPaint)

        canvas.withScale(currentScale, currentScale, cx, cy) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && useShader && renderer != null) {
                renderer.draw(this, width, height, flowTime, activity(), tint, currentTint)
            } else {
                drawFallback(this, cx, cy)
                if (currentTint > 0.01f) drawFallbackTint(this, cx, cy)
            }
        }

        if (mode == Mode.THINKING) drawSpinner(canvas, cx, cy, motionEnabled)

        if (motionEnabled) {
            if (useShader) postInvalidateOnAnimation() else postInvalidateDelayed(FALLBACK_FRAME_MS)
        } else if (abs(currentScale - scaleTarget()) > 0.004f) {
            postInvalidateDelayed(FALLBACK_FRAME_MS)
        }
    }

    override fun onDetachedFromWindow() {
        lastFrameNanos = 0L
        super.onDetachedFromWindow()
    }

    private fun updateMotion(deltaSeconds: Float, motionEnabled: Boolean) {
        val tintTarget = if (mode == Mode.DONE || mode == Mode.ERROR) 1f else 0f
        if (!motionEnabled) {
            currentLevel = 0f
            currentScale = scaleTarget()
            currentTint = tintTarget
            return
        }
        val levelRate = if (targetLevel > currentLevel) LEVEL_ATTACK_RATE else LEVEL_RELEASE_RATE
        currentLevel = damp(currentLevel, targetLevel, levelRate, deltaSeconds)
        currentTint = damp(currentTint, tintTarget, TINT_RATE, deltaSeconds)
        val target = scaleTarget()
        currentScale = damp(currentScale, target, if (target > currentScale) SCALE_ATTACK_RATE else SCALE_RELEASE_RATE, deltaSeconds)
        // Speaking flows backwards through the field: the same cloud, visibly a different voice.
        flowTime += deltaSeconds * flowSpeed() * if (mode == Mode.SPEAKING) -1f else 1f
    }

    private fun scaleTarget(): Float = when (mode) {
        Mode.LISTENING -> ACTIVE_SCALE + currentLevel * VOICE_GROW
        Mode.SPEAKING -> ACTIVE_SCALE + currentLevel * VOICE_GROW
        Mode.THINKING -> THINK_SCALE
        else -> IDLE_SCALE
    }

    private fun shouldAnimate(): Boolean = !paused && isShown && ValueAnimator.areAnimatorsEnabled()

    private fun flowSpeed(): Float = when (mode) {
        Mode.LISTENING -> 0.85f + currentLevel * 2.60f
        Mode.SPEAKING -> 0.70f + currentLevel * 2.20f
        Mode.THINKING -> 0.24f
        Mode.DONE, Mode.ERROR -> 0.30f
        Mode.IDLE -> 0.16f
    }

    private fun activity(): Float = when (mode) {
        Mode.LISTENING -> 0.35f + currentLevel * 1.05f
        Mode.SPEAKING -> 0.30f + currentLevel * 0.95f
        Mode.THINKING -> 0.10f
        Mode.DONE, Mode.ERROR -> 0.14f
        Mode.IDLE -> 0.12f
    }

    private fun drawFallback(canvas: Canvas, cx: Float, cy: Float) {
        val radius = min(width, height) / 2f
        canvas.withRotation(sin(flowTime * 0.8f) * 5f, cx, cy) {
            drawCircle(cx, cy, radius, fallbackBasePaint)
            drawCircle(cx, cy, radius, fallbackMistPaint)
        }
    }

    private fun drawFallbackTint(canvas: Canvas, cx: Float, cy: Float) {
        fallbackTintPaint.color = Color.argb(
            (currentTint * 210f).toInt().coerceIn(0, 255),
            (tint[0] * 255f).toInt(), (tint[1] * 255f).toInt(), (tint[2] * 255f).toInt(),
        )
        canvas.drawCircle(cx, cy, min(width, height) / 2f, fallbackTintPaint)
    }

    private fun drawSpinner(canvas: Canvas, cx: Float, cy: Float, motionEnabled: Boolean) {
        val radius = min(width, height) * 0.075f
        val angle = if (motionEnabled) (flowTime * 260f) % 360f else 45f
        canvas.drawArc(cx - radius, cy - radius, cx + radius, cy + radius, angle, 245f, false, spinnerPaint)
    }

    private fun damp(current: Float, target: Float, rate: Float, deltaSeconds: Float): Float =
        current + (target - current) * (1f - exp(-rate * deltaSeconds))

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private class RuntimeCloudRenderer {
        private val shader = RuntimeShader(CLOUD_SHADER)
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = this@RuntimeCloudRenderer.shader }

        fun draw(canvas: Canvas, width: Int, height: Int, time: Float, activity: Float, tint: FloatArray, tintStrength: Float) {
            shader.setFloatUniform("u_resolution", width.toFloat(), height.toFloat())
            shader.setFloatUniform("u_time", time)
            shader.setFloatUniform("u_activity", activity)
            shader.setFloatUniform("u_tint", tint[0], tint[1], tint[2])
            shader.setFloatUniform("u_tintStrength", tintStrength)
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        }
    }

    private companion object {
        // Sizes as a fraction of the view: room to grow with the voice without leaving the bounds.
        const val IDLE_SCALE = 0.80f
        const val ACTIVE_SCALE = 0.86f
        const val THINK_SCALE = 0.84f
        const val VOICE_GROW = 0.12f
        const val LEVEL_ATTACK_RATE = 13f
        const val LEVEL_RELEASE_RATE = 6f
        const val SCALE_ATTACK_RATE = 13f
        const val SCALE_RELEASE_RATE = 6f
        const val TINT_RATE = 7f
        const val MAX_DELTA_SECONDS = 0.05f
        const val PAUSED_ALPHA = 0.45f
        const val FALLBACK_FRAME_MS = 33L

        val DONE_TINT = floatArrayOf(0.18f, 0.77f, 0.71f)
        val ERROR_TINT = floatArrayOf(0.94f, 0.36f, 0.33f)

        // Parley's sky: violet depth above, periwinkle, a milky horizon, teal below (see Theme.kt).
        const val DEEP = 0xFF6654F2.toInt()
        const val UPPER = 0xFF7394FA.toInt()
        const val LOWER = 0xFF8CDBDB.toInt()
        const val MILK = 0xFFE6F2FC.toInt()
        const val HALO = 0x667394FA
        const val SPINNER = 0xFF5B47D6.toInt()

        /** AGSL port of orb-ui's MIT-licensed CloudTheme fragment shader, Parley palette, plus a tint pass. */
        const val CLOUD_SHADER = """
            uniform float2 u_resolution;
            uniform float u_time;
            uniform float u_activity;
            uniform float3 u_tint;
            uniform float u_tintStrength;

            float hash(float2 p) {
                p = fract(p * float2(123.34, 456.21));
                p += dot(p, p + 45.32);
                return fract(p.x * p.y);
            }

            float noise(float2 p) {
                float2 i = floor(p);
                float2 f = fract(p);
                float2 u = f * f * (3.0 - 2.0 * f);
                return mix(
                    mix(hash(i), hash(i + float2(1.0, 0.0)), u.x),
                    mix(hash(i + float2(0.0, 1.0)), hash(i + float2(1.0, 1.0)), u.x),
                    u.y
                );
            }

            float fbm(float2 p) {
                float value = 0.0;
                float amplitude = 0.52;
                float2x2 rotation = float2x2(0.80, 0.60, -0.60, 0.80);
                for (int octave = 0; octave < 5; octave++) {
                    value += amplitude * noise(p);
                    p = rotation * p * 1.92 + float2(9.7, 4.3);
                    amplitude *= 0.5;
                }
                return value;
            }

            half4 main(float2 fragCoord) {
                // AGSL uses a top-left origin; flip Y to match WebGL's gl_FragCoord.
                float2 uv = float2(fragCoord.x, u_resolution.y - fragCoord.y) / u_resolution;
                float2 centered = uv - 0.5;
                float radius = length(centered);
                float edge = 1.0 - smoothstep(0.488, 0.5, radius);

                float2 p = centered * 2.0;
                float t = u_time;
                float2 warp = float2(
                    fbm(p * 1.02 + float2(t * 0.34, -t * 0.24)),
                    fbm(p * 1.08 + float2(-t * 0.27, t * 0.32) + float2(6.7, 2.9))
                );
                float2 curl = float2(
                    sin(p.y * 2.4 + t * 0.68 + warp.y * 3.2),
                    cos(p.x * 2.1 - t * 0.61 + warp.x * 3.0)
                );
                float2 warped =
                    p +
                    (warp - 0.5) * (1.10 + u_activity * 0.75) +
                    curl * (0.035 + u_activity * 0.18);
                float broad = fbm(warped * 0.92 + float2(t * 0.14, -t * 0.18));
                float folded = fbm(warped * 1.66 + float2(-t * 0.23, t * 0.19) + 5.2);
                float field = mix(broad, folded, 0.3 + u_activity * 0.30);

                float horizon =
                    0.46 +
                    0.08 * sin((uv.x + warp.x * 0.2) * 5.4 + t * 0.42) +
                    0.16 * (broad - 0.5);
                float upper = smoothstep(horizon - 0.12, horizon + 0.08, uv.y);
                float band = exp(-pow((uv.y - horizon) * (5.2 + u_activity * 0.8), 2.0));
                float cloud = smoothstep(0.24, 0.79, field);

                float3 deep = float3(0.40, 0.33, 0.95);
                float3 upperSky = float3(0.45, 0.58, 0.98);
                float3 lowerSky = float3(0.55, 0.86, 0.86);
                float3 milk = float3(0.90, 0.95, 0.99);

                float3 color = mix(lowerSky, upperSky, upper);
                float upperDepth = upper * (0.14 + smoothstep(0.42, 0.78, folded) * 0.5);
                color = mix(color, deep, upperDepth);
                float milkAmount = clamp(band * (0.38 + cloud * 0.55), 0.0, 0.82);
                color = mix(color, milk, milkAmount);
                float lowerMist = (1.0 - upper) * smoothstep(0.58, 0.9, broad) * 0.18;
                color = mix(color, milk, lowerMist);
                float grain = (noise(fragCoord * 0.64) - 0.5) / 255.0;
                color += grain;

                color = mix(color, u_tint, clamp(u_tintStrength, 0.0, 1.0) * 0.62);
                return half4(color * edge, edge);
            }
        """
    }
}
