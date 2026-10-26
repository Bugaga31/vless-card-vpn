package com.vlesscardvpn.ai

import android.app.Service
import android.content.Intent
import android.os.*
import java.io.File
import java.util.concurrent.Executors

object LocalAiNative {
    init { System.loadLibrary("equilibrium_ai") }
    external fun generate(path: String, prompt: ByteArray, planner: Boolean): ByteArray
}

/** A bound-only, unexported process. It has no Store/Actions initialization and cannot invoke VPN actions. */
class LocalAiService : Service() {
    private val executor = Executors.newSingleThreadExecutor()
    private var busy = false
    private val main = Handler(Looper.getMainLooper())
    private val messenger = Messenger(Handler(Looper.getMainLooper()) { msg ->
        if (msg.what != 1 || busy) return@Handler true
        val reply = msg.replyTo ?: return@Handler true
        val model = LocalAiPolicy.models.firstOrNull { it.id == msg.data.getString("model") } ?: return@Handler true
        val prompt = msg.data.getString("prompt") ?: return@Handler true
        val file = File(filesDir, "local-chat/${model.id}.gguf")
        if (prompt.length > 6000 || file.length() != model.bytes || !LocalAi.readyFor(this, model)) {
            runCatching { reply.send(Message.obtain(null, 2).apply { data = Bundle().apply { putString("error", "resources_or_model") } }) }
            return@Handler true
        }
        val planner = msg.data.getBoolean("planner", false)
        busy = true
        // Native timeout is cooperative; this process-only watchdog covers a stuck kernel/model load too.
        val watchdog = Runnable { android.os.Process.killProcess(android.os.Process.myPid()) }
        main.postDelayed(watchdog, 42000)
        val energyWatch = object : Runnable {
            override fun run() {
                if (!LocalAi.energyReady(this@LocalAiService)) {
                    runCatching { reply.send(Message.obtain(null, 2).apply { data = Bundle().apply { putString("error", "energy_limit") } }) }
                    main.postDelayed(watchdog, 100)
                } else main.postDelayed(this, 2000)
            }
        }
        main.postDelayed(energyWatch, 2000)
        executor.execute {
            val result = runCatching { LocalAiNative.generate(file.absolutePath, prompt.toByteArray(Charsets.UTF_8), planner).toString(Charsets.UTF_8) }
            main.post {
                main.removeCallbacks(watchdog); main.removeCallbacks(energyWatch)
                runCatching { reply.send(Message.obtain(null, 2).apply { data = Bundle().apply {
                    if (result.isSuccess) putString("text", result.getOrThrow().take(4096)) else putString("error", result.exceptionOrNull()?.message?.takeIf {
                        it in setOf("model_load", "context_init", "context_limit", "generation_timeout", "prefill_timeout", "tokenize", "decode", "empty_output", "planner_grammar")
                    } ?: "inference_failed")
                } }) }
            }
        }
        true
    })
    override fun onBind(intent: Intent): IBinder = messenger.binder
    override fun onUnbind(intent: Intent): Boolean {
        // Only :local_ai dies; the VPN and UI are in the main process.
        android.os.Process.killProcess(android.os.Process.myPid())
        return false
    }
    override fun onDestroy() { executor.shutdownNow(); super.onDestroy() }
}
