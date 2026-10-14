package com.portal.calendar

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack

/** Plays one synthesized sound at a time; a newer sound replaces one that is still playing. */
object Sfx {
    @Volatile private var track: AudioTrack? = null

    fun play(data: ShortArray, rate: Int) {
        stop()
        runCatching {
            val t = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(rate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(data.size * 2)
                .build()
            t.write(data, 0, data.size)
            t.setNotificationMarkerPosition(data.size)
            t.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
                override fun onMarkerReached(at: AudioTrack) { if (track === at) stop() else at.release() }
                override fun onPeriodicNotification(at: AudioTrack) {}
            })
            track = t
            t.play()
        }
    }

    fun stop() {
        val t = track ?: return
        track = null
        runCatching { t.stop() }
        runCatching { t.release() }
    }
}
