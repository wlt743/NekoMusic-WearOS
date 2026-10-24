package com.neko.music.wear.player

import android.content.Context
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.exoplayer.ExoPlayer
import com.neko.music.wear.data.Music

/** 全局唯一的播放器实例，UI 与 MediaSessionService 共用。 */
object PlayerManager {
    @Volatile
    private var instance: ExoPlayer? = null

    fun get(context: Context): ExoPlayer {
        instance?.let { return it }
        synchronized(this) {
            instance?.let { return it }
            val player = ExoPlayer.Builder(context.applicationContext).build()
            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                true,
            )
            instance = player
            return player
        }
    }

    fun play(context: Context, url: String, music: Music) {
        val player = get(context)
        val item = MediaItem.Builder()
            .setUri(url)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(music.title)
                    .setArtist(music.artist)
                    .setArtworkUri(Uri.parse(music.coverUrl))
                    .build(),
            )
            .build()
        player.setMediaItem(item)
        player.prepare()
        player.play()
    }
}
