package net.devemperor.parley.audio

import android.Manifest
import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import androidx.annotation.RequiresPermission
import net.devemperor.parley.core.audio.Pcm

/**
 * Streams the watch microphone as 16-bit mono PCM at the provider's rate.
 *
 * The VOICE_COMMUNICATION source asks the platform for its call-grade processing, including echo
 * cancellation against the watch's own speaker; the explicit effects are switched on where the
 * device offers them. Some watches refuse 24 kHz, then 16 kHz is recorded and resampled.
 */
class Microphone(
    private val sampleRate: Int,
    private val onAudio: (ByteArray) -> Unit,
    private val onLevel: (Float) -> Unit,
) {
    @Volatile private var running = false
    private var thread: Thread? = null

    @SuppressLint("MissingPermission")
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun start(): Boolean {
        if (running) return true
        val (record, recordRate) = open(sampleRate) ?: open(FALLBACK_RATE)?.takeIf { sampleRate != FALLBACK_RATE } ?: return false
        val effects = listOfNotNull(
            if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(record.audioSessionId) else null,
            if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(record.audioSessionId) else null,
            if (AutomaticGainControl.isAvailable()) AutomaticGainControl.create(record.audioSessionId) else null,
        ).onEach { runCatching { it.enabled = true } }

        running = true
        thread = Thread({
            val frame = ByteArray(Pcm.bytesFor(FRAME_MS, recordRate))
            val chunk = java.io.ByteArrayOutputStream()
            try {
                record.startRecording()
                while (running) {
                    val read = record.read(frame, 0, frame.size)
                    if (read <= 0) continue
                    onLevel(Pcm.level(frame, read))
                    chunk.write(frame, 0, read)
                    if (chunk.size() >= Pcm.bytesFor(CHUNK_MS, recordRate)) {
                        val pcm = chunk.toByteArray()
                        chunk.reset()
                        onAudio(Pcm.resample(pcm, recordRate, sampleRate))
                    }
                }
            } catch (e: Exception) {
                Log.w("Parley", "Microphone stopped: ${e.message}")
            } finally {
                runCatching { record.stop() }
                effects.forEach { runCatching { it.release() } }
                record.release()
            }
        }, "parley-mic").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
        return true
    }

    fun stop() {
        running = false
        thread?.join(500)
        thread = null
    }

    @SuppressLint("MissingPermission")
    private fun open(rate: Int): Pair<AudioRecord, Int>? {
        val minBuffer = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuffer <= 0) return null
        val record = runCatching {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                rate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuffer * 2, Pcm.bytesFor(200, rate)),
            )
        }.getOrNull() ?: return null
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return null
        }
        return record to rate
    }

    private companion object {
        const val FALLBACK_RATE = 16_000
        const val FRAME_MS = 20L
        /** Providers recommend chunks of about 100 ms. */
        const val CHUNK_MS = 100L
    }
}
