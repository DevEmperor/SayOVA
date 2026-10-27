package net.devemperor.parley.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import net.devemperor.parley.core.audio.Pcm
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Plays the streamed answer and knows how much of it has been heard, which the transcript uses to
 * highlight what was already said and the provider needs when an answer is cut short.
 *
 * Output goes to Bluetooth headphones when they are connected and to the watch speaker otherwise;
 * the platform routes the assistant usage that way by itself.
 */
class SpeechPlayer(private val sampleRate: Int) {

    private class Chunk(val turnKey: String, val pcm: ByteArray, val level: Float)

    private val queue = LinkedBlockingQueue<Chunk>()
    private val track: AudioTrack
    @Volatile private var running = true
    private val writer: Thread

    /** Frames handed to the track, per turn, in the order they were written. */
    private val turns = LinkedHashMap<String, LongRange>()
    private val levels = ArrayDeque<Pair<Long, Float>>()
    @Volatile private var framesWritten = 0L
    @Volatile private var flushBase = 0L
    /** Bumped by [flush], so the writer abandons the chunk it is in the middle of. */
    @Volatile private var generation = 0

    init {
        val minBuffer = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(maxOf(minBuffer * 2, Pcm.bytesFor(300, sampleRate)))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track.play()
        writer = Thread({
            while (running) {
                val chunk = runCatching { queue.poll(200, TimeUnit.MILLISECONDS) }.getOrNull() ?: continue
                val gen = generation
                val frames = chunk.pcm.size / 2L
                synchronized(this) {
                    val start = framesWritten
                    val range = turns[chunk.turnKey]
                    turns[chunk.turnKey] = (range?.first ?: start)..(start + frames)
                    levels.addLast(start to chunk.level)
                    while (levels.size > 400) levels.removeFirst()
                    framesWritten += frames
                }
                var offset = 0
                while (running && gen == generation && offset < chunk.pcm.size) {
                    val written = track.write(chunk.pcm, offset, chunk.pcm.size - offset)
                    if (written <= 0) break
                    offset += written
                }
            }
        }, "parley-player").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    fun enqueue(turnKey: String, pcm: ByteArray) {
        if (pcm.isNotEmpty()) queue.offer(Chunk(turnKey, pcm, Pcm.level(pcm)))
    }

    @Volatile private var released = false

    // After release() the native track is gone and every query on it throws; the conversation still
    // reads these while it winds down, so they answer "nothing (more) to play" instead.
    private val framesPlayed: Long
        get() = if (released) framesWritten else flushBase + (track.playbackHeadPosition.toLong() and 0xFFFFFFFFL)

    /** Milliseconds of [turnKey] the user has heard so far. */
    fun playedMs(turnKey: String): Long = synchronized(this) {
        val range = turns[turnKey] ?: return 0
        val played = (framesPlayed - range.first).coerceIn(0, range.last - range.first)
        played * 1000 / sampleRate
    }

    /** Milliseconds of [turnKey] that have arrived so far. */
    fun receivedMs(turnKey: String): Long = synchronized(this) {
        val range = turns[turnKey] ?: return queuedMs(turnKey)
        (range.last - range.first) * 1000 / sampleRate + queuedMs(turnKey)
    }

    private fun queuedMs(turnKey: String): Long =
        queue.filter { it.turnKey == turnKey }.sumOf { Pcm.durationMs(it.pcm.size, sampleRate) }

    /** True while something is still to be heard. */
    val isPlaying: Boolean
        get() = queue.isNotEmpty() || framesPlayed < framesWritten

    /** Loudness of what is audible right now, for the orb. */
    val level: Float
        get() = synchronized(this) {
            if (!isPlaying) return 0f
            val head = framesPlayed
            levels.lastOrNull { it.first <= head }?.second ?: 0f
        }

    /** Stops the answer at once and drops everything still queued. */
    fun flush() {
        if (released) return
        generation++
        queue.clear()
        synchronized(this) {
            track.pause()
            track.flush()
            // The head position restarts at zero after a flush; keep the totals continuous.
            flushBase = framesWritten
            track.play()
        }
    }

    fun release() {
        if (released) return
        released = true
        running = false
        queue.clear()
        writer.join(500)
        runCatching { track.stop() }
        track.release()
    }
}
