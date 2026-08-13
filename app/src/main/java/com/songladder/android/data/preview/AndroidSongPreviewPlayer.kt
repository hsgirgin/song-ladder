package com.songladder.android.data.preview

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import androidx.core.content.ContextCompat
import com.songladder.android.domain.repository.SongPreviewPlaybackEvent
import com.songladder.android.domain.repository.SongPreviewPlayer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow

class AndroidSongPreviewPlayer(context: Context) : SongPreviewPlayer {
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val mutableEvents = MutableSharedFlow<SongPreviewPlaybackEvent>(extraBufferCapacity = 2)
    override val events: Flow<SongPreviewPlaybackEvent> = mutableEvents

    private var mediaPlayer: MediaPlayer? = null
    private var currentSongId: String? = null
    private var isPrepared = false
    private var shouldPlay = false
    private var receiverRegistered = false

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> stop()
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> mediaPlayer?.setVolume(0.2f, 0.2f)
            AudioManager.AUDIOFOCUS_GAIN -> mediaPlayer?.setVolume(1f, 1f)
        }
    }
    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        .setAudioAttributes(audioAttributes())
        .setOnAudioFocusChangeListener(focusListener)
        .build()
    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) stop()
        }
    }

    override fun play(songId: String, url: String) {
        if (currentSongId == songId && mediaPlayer != null) {
            shouldPlay = true
            if (!requestAudioFocus()) error("Audio focus is unavailable.")
            if (isPrepared) mediaPlayer?.start()
            return
        }
        releasePlayer(notify = false)
        if (!requestAudioFocus()) error("Audio focus is unavailable.")

        try {
            currentSongId = songId
            shouldPlay = true
            isPrepared = false
            registerNoisyReceiver()
            val player = MediaPlayer()
            mediaPlayer = player
            player.apply {
                setAudioAttributes(audioAttributes())
                setDataSource(url)
                setOnPreparedListener {
                    isPrepared = true
                    if (shouldPlay) it.start()
                }
                setOnCompletionListener { releasePlayer(notify = true) }
                setOnErrorListener { _, _, _ ->
                    val failedSongId = currentSongId
                    releasePlayer(notify = false)
                    if (failedSongId != null) {
                        mutableEvents.tryEmit(SongPreviewPlaybackEvent(failedSongId, failed = true))
                    }
                    true
                }
                prepareAsync()
            }
        } catch (throwable: Throwable) {
            releasePlayer(notify = false)
            throw throwable
        }
    }

    override fun pause() {
        shouldPlay = false
        mediaPlayer?.runCatching { if (isPrepared && isPlaying) pause() }
        abandonAudioFocus()
    }

    override fun stop() {
        releasePlayer(notify = true)
    }

    private fun releasePlayer(notify: Boolean) {
        val stoppedSongId = currentSongId
        currentSongId = null
        shouldPlay = false
        isPrepared = false
        mediaPlayer?.runCatching { stop() }
        mediaPlayer?.release()
        mediaPlayer = null
        abandonAudioFocus()
        unregisterNoisyReceiver()
        if (notify && stoppedSongId != null) {
            mutableEvents.tryEmit(SongPreviewPlaybackEvent(stoppedSongId))
        }
    }

    private fun requestAudioFocus(): Boolean =
        audioManager.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED

    private fun abandonAudioFocus() {
        audioManager.abandonAudioFocusRequest(focusRequest)
    }

    private fun registerNoisyReceiver() {
        if (receiverRegistered) return
        ContextCompat.registerReceiver(
            appContext,
            noisyReceiver,
            IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        receiverRegistered = true
    }

    private fun unregisterNoisyReceiver() {
        if (!receiverRegistered) return
        appContext.unregisterReceiver(noisyReceiver)
        receiverRegistered = false
    }

    private fun audioAttributes() = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()
}
