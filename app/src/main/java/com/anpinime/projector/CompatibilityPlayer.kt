package com.anpinime.projector

import android.content.Context
import android.net.Uri
import android.view.ViewGroup
import android.widget.FrameLayout
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout

/** Loaded only when requested or the device decoder cannot handle a stream. */
internal class CompatibilityPlayer(context: Context, parent: FrameLayout) {
    private val engine = LibVLC(context, arrayListOf("--audio-time-stretch", "--network-caching=1500", "--no-video-title-show"))
    private val view = VLCVideoLayout(context)
    private val player = MediaPlayer(engine)
    private var closed = false
    val position: Long get() = player.time.coerceAtLeast(0)
    val duration: Long get() = player.length.coerceAtLeast(0)
    val playing: Boolean get() = player.isPlaying

    init {
        parent.addView(view, 0, FrameLayout.LayoutParams(-1, -1))
        player.attachViews(view, null, true, false)
    }

    fun start(url: String, headers: Map<String, String>, position: Long, onReady: () -> Unit,
              onEnd: () -> Unit, onError: () -> Unit) {
        var resumed = false
        player.setEventListener { event ->
            if (!closed) when (event.type) {
                MediaPlayer.Event.Playing -> {
                    if (!resumed) {
                        resumed = true
                        if (position > 0) player.setTime(position)
                    }
                    onReady()
                }
                MediaPlayer.Event.EndReached -> onEnd()
                MediaPlayer.Event.EncounteredError -> onError()
            }
        }
        val media = Media(engine, Uri.parse(url))
        media.setHWDecoderEnabled(true, false)
        headers.entries.firstOrNull { it.key.equals("User-Agent", true) }?.value
            ?.let { media.addOption(":http-user-agent=$it") }
        headers.entries.firstOrNull { it.key.equals("Referer", true) }?.value
            ?.let { media.addOption(":http-referrer=$it") }
        media.addOption(":audio-language=vi,en")
        media.addOption(":sub-language=vi,en")
        player.media = media
        media.release()
        player.play()
    }

    fun play() = player.play()
    fun pause() = player.pause()
    fun seekTo(position: Long) { if (player.isSeekable) player.setTime(position.coerceIn(0, duration)) }
    fun tracks(subtitles: Boolean): List<Pair<Int, String>> =
        (if (subtitles) player.spuTracks else player.audioTracks)?.map { it.id to it.name }.orEmpty()
    fun selectTrack(subtitles: Boolean, id: Int) {
        if (subtitles) player.setSpuTrack(id) else player.setAudioTrack(id)
    }
    fun release() {
        if (closed) return
        closed = true
        player.setEventListener(null)
        player.stop()
        player.detachViews()
        player.release()
        engine.release()
        (view.parent as? ViewGroup)?.removeView(view)
    }
}
