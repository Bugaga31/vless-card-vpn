package com.vlesscardvpn.core

import android.content.Context
import android.util.Log
import go.Seq
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import libv2ray.Libv2ray

/** Thin wrapper over Xray-core (2dust/AndroidLibXrayLite). Geo files are read from APK assets by the core. */
object XrayCore {
    private const val TAG = "XrayCore"
    @Volatile private var initialized = false

    @Synchronized
    fun init(context: Context) {
        if (initialized) return
        Seq.setContext(context.applicationContext)
        val dir = context.filesDir.resolve("xray").apply { mkdirs() }
        // Xray 26 opens geoip.dat/geosite.dat by path (no asset fallback), so copy them out of the APK once per version.
        val marker = dir.resolve(".assets-version")
        val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime.toString() }.getOrDefault("0")
        if (marker.takeIf { it.isFile }?.readText() != version || listOf("geoip.dat", "geosite.dat").any { !dir.resolve(it).isFile }) {
            for (name in listOf("geoip.dat", "geosite.dat")) {
                val file = android.util.AtomicFile(dir.resolve(name))
                val out = file.startWrite()
                try {
                    val copied = context.assets.open(name).use { it.copyTo(out) }
                    check(copied > 0) { "Пустой ресурс Xray: $name" }
                    file.finishWrite(out)
                } catch (e: Exception) { file.failWrite(out); throw e }
            }
            marker.writeText(version)
        }
        Libv2ray.initCoreEnv(dir.absolutePath, "")
        initialized = true
    }

    fun version(): String = if (!initialized) "Xray 26.9.30 · запуск по необходимости" else runCatching { Libv2ray.checkVersionX() }.getOrDefault("?")

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
        fun start(config: String) { controller.startLoop(RuntimeXrayConfig.prepare(config), 0) }
        fun stop() { runCatching { controller.stopLoop() } }
        /** HTTP(S) GET through the running instance's routing; returns ms or -1. */
        fun delay(url: String): Long = runCatching { controller.measureDelay(url) }.getOrDefault(-1L)
    }
}
