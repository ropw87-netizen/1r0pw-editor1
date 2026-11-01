package com.r0pw.editor.engine

import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import com.r0pw.editor.model.Layer
import com.r0pw.editor.model.Project
import kotlin.math.abs

/** Plays the audio of audio/video layers in sync with the preview playhead. */
class AudioPreview(private val ctx: Context) {
    private class Slot(val player: MediaPlayer, var ready: Boolean, var started: Boolean)

    private val slots = HashMap<String, Slot>()

    private fun slotFor(l: Layer): Slot? {
        slots[l.id]?.let { return it }
        val src = l.source ?: return null
        return try {
            val mp = MediaPlayer()
            mp.setDataSource(ctx, Uri.parse(src))
            val s = Slot(mp, false, false)
            mp.setOnPreparedListener { s.ready = true }
            mp.prepareAsync()
            slots[l.id] = s
            s
        } catch (e: Exception) {
            null
        }
    }

    /** Call every frame while playing (and once on play/seek). */
    fun sync(p: Project, t: Float, playing: Boolean) {
        val live = HashSet<String>()
        for (l in p.layers) {
            if (!l.hasAudioSource() || !l.visible) continue
            live.add(l.id)
            val inRange = t >= l.start && t < l.end
            val s = slotFor(l) ?: continue
            if (!s.ready) continue
            try {
                if (playing && inRange) {
                    val lt = t - l.start
                    val vol = (l.v("volume", lt, 100f) / 100f).coerceIn(0f, 2f)
                    var g = vol
                    if (l.fadeIn > 0.001f && lt < l.fadeIn) g *= lt / l.fadeIn
                    val rem = l.duration - lt
                    if (l.fadeOut > 0.001f && rem < l.fadeOut) g *= rem / l.fadeOut
                    val v = g.coerceIn(0f, 1f)
                    s.player.setVolume(v, v)
                    val want = ((l.inPoint + lt) * 1000f).toInt()
                    if (!s.started) {
                        s.player.seekTo(want)
                        s.player.start()
                        s.started = true
                    } else if (abs(s.player.currentPosition - want) > 350) {
                        s.player.seekTo(want)
                    }
                } else if (s.started) {
                    s.player.pause()
                    s.started = false
                }
            } catch (e: Exception) {
                // ignore player state errors
            }
        }
        // drop players of removed layers
        val it = slots.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (e.key !in live) {
                try { e.value.player.release() } catch (x: Exception) { }
                it.remove()
            }
        }
    }

    fun pauseAll() {
        for (s in slots.values) {
            try {
                if (s.started) s.player.pause()
            } catch (e: Exception) {
            }
            s.started = false
        }
    }

    fun release() {
        for (s in slots.values) try { s.player.release() } catch (e: Exception) { }
        slots.clear()
    }
}
