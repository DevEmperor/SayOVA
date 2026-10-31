package net.devemperor.talova.core.audio

import kotlin.math.log10
import kotlin.math.sqrt

/** Helpers for mono 16-bit little-endian PCM, the format every provider streams. */
object Pcm {

    fun durationMs(bytes: Int, sampleRate: Int): Long = bytes / 2 * 1000L / sampleRate

    fun bytesFor(ms: Long, sampleRate: Int): Int = (ms * sampleRate / 1000L).toInt() * 2

    /**
     * Loudness of a chunk mapped to 0..1 for the UI: -60 dBFS and below is 0, 0 dBFS is 1. A linear RMS
     * would leave speech in the bottom fifth of the range.
     */
    fun level(pcm: ByteArray, length: Int = pcm.size): Float {
        val samples = length / 2
        if (samples == 0) return 0f
        var sum = 0.0
        for (i in 0 until samples) {
            val s = sample(pcm, i).toDouble()
            sum += s * s
        }
        val rms = sqrt(sum / samples) / 32768.0
        if (rms <= 0.0) return 0f
        val db = 20 * log10(rms)
        return ((db + 60.0) / 60.0).coerceIn(0.0, 1.0).toFloat()
    }

    /**
     * Linear-interpolating resampler. Speech has little above 8 kHz, so the aliasing a plain linear
     * filter allows between 16 and 24 kHz is inaudible; it only runs when the microphone refuses the
     * provider's rate.
     */
    fun resample(pcm: ByteArray, fromRate: Int, toRate: Int): ByteArray {
        if (fromRate == toRate) return pcm
        val inSamples = pcm.size / 2
        if (inSamples == 0) return ByteArray(0)
        val outSamples = (inSamples.toLong() * toRate / fromRate).toInt()
        val out = ByteArray(outSamples * 2)
        val step = fromRate.toDouble() / toRate
        for (i in 0 until outSamples) {
            val pos = i * step
            val index = pos.toInt().coerceAtMost(inSamples - 1)
            val next = (index + 1).coerceAtMost(inSamples - 1)
            val frac = pos - index
            val value = (sample(pcm, index) * (1 - frac) + sample(pcm, next) * frac).toInt()
            out[2 * i] = (value and 0xFF).toByte()
            out[2 * i + 1] = ((value shr 8) and 0xFF).toByte()
        }
        return out
    }

    fun silence(ms: Long, sampleRate: Int): ByteArray = ByteArray(bytesFor(ms, sampleRate))

    private fun sample(pcm: ByteArray, index: Int): Int =
        (pcm[2 * index].toInt() and 0xFF) or (pcm[2 * index + 1].toInt() shl 8)
}

/**
 * Holds audio back until the server has accepted the session configuration, then replays it. Gemini
 * rejects audio before `setupComplete`, and OpenAI would decode it with the wrong format before
 * `session.updated`; dropping it instead would cut off the user's first words.
 */
class AudioGate(private val maxBufferedBytes: Int) {
    private val pending = ArrayDeque<ByteArray>()
    private var pendingBytes = 0
    private var sink: ((ByteArray) -> Unit)? = null

    @Synchronized
    fun send(pcm: ByteArray) {
        val ready = sink
        if (ready != null) {
            ready(pcm)
            return
        }
        pending.addLast(pcm)
        pendingBytes += pcm.size
        while (pendingBytes > maxBufferedBytes && pending.isNotEmpty()) {
            pendingBytes -= pending.removeFirst().size
        }
    }

    @Synchronized
    fun open(sink: (ByteArray) -> Unit) {
        while (pending.isNotEmpty()) sink(pending.removeFirst())
        pendingBytes = 0
        this.sink = sink
    }

    /** Back to buffering, e.g. while a dropped connection is resumed. */
    @Synchronized
    fun hold() {
        sink = null
    }

    companion object {
        /**
         * How much audio a session holds back: a question asked while it connects. Waking the watch's
         * Wi-Fi and the handshake can take some seconds; beyond this the oldest audio goes.
         */
        const val HOLD_MS = 8_000L
    }
}
