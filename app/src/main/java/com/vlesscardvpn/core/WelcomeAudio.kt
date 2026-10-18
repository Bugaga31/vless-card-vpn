package com.vlesscardvpn.core

import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.view.accessibility.AccessibilityManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/** Built-in short bell + installed offline English TTS. No cloud audio, mic or downloaded voice. */
class WelcomeAudio(private val context: Context, private val owner: LifecycleOwner) {
    companion object { private val policy = WelcomePolicy() }
    private var job: Job? = null
    private var tts: TextToSpeech? = null
    @Volatile private var closed = false
    private fun audible(): Boolean = runCatching {
        val audio = context.getSystemService(AudioManager::class.java)
        val notifications = context.getSystemService(NotificationManager::class.java)
        audio.ringerMode == AudioManager.RINGER_MODE_NORMAL && audio.mode == AudioManager.MODE_NORMAL &&
            !audio.isMusicActive && audio.getStreamVolume(AudioManager.STREAM_MUSIC) > 0 &&
            notifications.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_ALL
    }.getOrDefault(false)
    fun start() {
        if (closed || job != null) return
        job = owner.lifecycleScope.launch {
            // A notification/permission dialog may briefly pause the activity on a fresh install.
            // Only consume the install marker after a stable foreground resume, not underneath that dialog.
            delay(500)
            if (!owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return@launch
            val firstLaunch = withContext(Dispatchers.IO) {
                WelcomePolicy.claimFirstLaunch(java.io.File(context.noBackupFilesDir, "welcome-first-launch-v1"))
            }
            if (!policy.claim(SystemClock.elapsedRealtime(), Store.state.value.settings.welcomeSound, audible())) return@launch
            withContext(Dispatchers.IO) { runCatching { bell() } }
            if (closed || !audible() || !Store.state.value.settings.welcomeSound ||
                !owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return@launch
            if (!firstLaunch) return@launch
            if (context.getSystemService(AccessibilityManager::class.java)?.isTouchExplorationEnabled == true) return@launch
            tts = runCatching { TextToSpeech(context.applicationContext) { status ->
                // Callback is posted after construction; never speak after the activity leaves foreground.
                owner.lifecycleScope.launch speech@ {
                    runCatching {
                        val engine = tts ?: return@speech
                        if (status != TextToSpeech.SUCCESS || closed || !audible() || !Store.state.value.settings.welcomeSound ||
                            !owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return@speech
                        val voice = engine.voices?.filter { it.locale.language == Locale.ENGLISH.language && !it.isNetworkConnectionRequired }
                            ?.sortedByDescending { it.locale == Locale.US }?.firstOrNull() ?: return@speech
                        engine.voice = voice
                        engine.setSpeechRate(.92f)
                        engine.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                        engine.speak("Thank you for installing me.", TextToSpeech.QUEUE_FLUSH,
                            android.os.Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, .6f) }, "welcome")
                    }
                }
            } }.getOrNull()
        }
    }
    private fun bell() {
        val rate = 22050
        val samples = ShortArray(rate * 45 / 100) { i ->
            val t = i.toDouble() / rate
            val a = sin(2 * PI * 1046.5 * t) * exp(-10 * t)
            val b = if (t >= .08) sin(2 * PI * 1568 * (t - .08)) * exp(-12 * (t - .08)) else 0.0
            val attack = (t / .008).coerceAtMost(1.0)
            ((a + b * .6) * attack * 2600).toInt().coerceIn(-32767, 32767).toShort()
        }
        if (closed || !audible()) return
        val track = AudioTrack.Builder().setAudioAttributes(AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(samples.size * 2).setTransferMode(AudioTrack.MODE_STATIC).build()
        try {
            track.write(samples, 0, samples.size)
            if (!closed) { track.play(); var waited = 0; while (!closed && waited < 450) { Thread.sleep(25); waited += 25 } }
        } finally { runCatching { track.stop() }; track.release() }
    }
    fun pause() { job?.cancel(); job = null; runCatching { tts?.stop() } }
    fun close() { closed = true; job?.cancel(); tts?.stop(); tts?.shutdown(); tts = null }
}
