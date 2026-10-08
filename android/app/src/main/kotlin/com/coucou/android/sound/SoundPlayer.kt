package com.coucou.android.sound

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import com.coucou.android.mochi.SoundSink

/** Volume rules from docs/SPEC.md: default 0.12 and the slider never goes above 0.2. */
object SoundVolume {
    const val DEFAULT = 0.12f
    const val MAX = 0.2f
    fun clamp(v: Float): Float = v.coerceIn(0f, MAX)
}

/**
 * Plays Mochi's sounds (the .wav files in res/raw, (c) Louis Raillé, used with permission) through a
 * SoundPool. Short clips, no streaming; a sound that is not found is silently ignored.
 */
class SoundPlayer(private val context: Context) : SoundSink {
    var enabled = true
    var volume = SoundVolume.DEFAULT
        set(v) { field = SoundVolume.clamp(v) }

    private val pool: SoundPool = SoundPool.Builder()
        .setMaxStreams(4)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build(),
        )
        .build()
    private val ids = HashMap<String, Int>()
    private val ready = HashSet<Int>()

    init {
        pool.setOnLoadCompleteListener { _, id, status -> if (status == 0) ready.add(id) }
    }

    private fun load(name: String): Int? = ids.getOrPut(name) {
        val res = context.resources.getIdentifier(name, "raw", context.packageName)
        if (res == 0) return null
        pool.load(context, res, 1)
    }

    override fun play(name: String) {
        if (!enabled || volume <= 0f) return
        val id = load(name) ?: return
        if (id in ready) pool.play(id, volume, volume, 1, 0, 1f)
    }

    fun release() = pool.release()
}
