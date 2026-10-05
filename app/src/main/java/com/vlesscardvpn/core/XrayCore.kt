package com.vlesscardvpn.core

import android.content.Context
import android.util.Log
import go.Seq
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import libv2ray.Libv2ray
import java.util.concurrent.atomic.AtomicBoolean

/** Thin wrapper over Xray-core (2dust/AndroidLibXrayLite). Geo files are read from APK assets by the core. */
object XrayCore {
    private const val TAG = "XrayCore"
    private val initialized = AtomicBoolean(false)

    fun init(context: Context) {
        if (!initialized.compareAndSet(false, true)) return
        Seq.setContext(context.applicationContext)
        val dir = context.filesDir.resolve("xray").apply { mkdirs() }
        Libv2ray.initCoreEnv(dir.absolutePath, "")
    }

    fun version(): String = runCatching { Libv2ray.checkVersionX() }.getOrDefault("?")

    /** A separate core instance. The VPN uses one, the tester another one at the same time. */
    class Instance(private val name: String) {
        @Volatile var lastStatus: String = ""
        private val controller: CoreController = Libv2ray.newCoreController(object : CoreCallbackHandler {
            override fun startup(): Long = 0
            override fun shutdown(): Long = 0
            override fun onEmitStatus(code: Long, msg: String?): Long { lastStatus = msg.orEmpty(); Log.i(TAG, "$name: $msg"); return 0 }
        })
        val running: Boolean get() = runCatching { controller.isRunning }.getOrDefault(false)
        /** Throws with the core's message if the config is rejected. */
        fun start(config: String) { controller.startLoop(config, 0) }
        fun stop() { runCatching { controller.stopLoop() } }
        /** HTTP(S) GET through the running instance's routing; returns ms or -1. */
        fun delay(url: String): Long = runCatching { controller.measureDelay(url) }.getOrDefault(-1L)
    }
}
