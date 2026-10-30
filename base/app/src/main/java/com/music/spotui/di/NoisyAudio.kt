package com.music.spotui.di

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager

/**
 * Headphones unplugged or a Bluetooth headset gone: the music pauses instead of
 * moving to the phone's speaker, like every music app. Music playing on another
 * Connect device is left alone (this phone is only the remote).
 */
object NoisyAudio {
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != AudioManager.ACTION_AUDIO_BECOMING_NOISY) return
            if (SongPlayer.activeDevice.value == null && SongPlayer.isPlaying()) SongPlayer.pause(by = "headphones unplugged")
        }
    }

    fun register(context: Context) {
        // A system broadcast: no export flag needed, but API 33+ asks for one anyway.
        val filter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        if (android.os.Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        else context.registerReceiver(receiver, filter)
    }
}
