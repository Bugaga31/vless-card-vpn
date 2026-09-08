package com.vlesscardvpn.core

import android.content.Context
import android.os.ParcelFileDescriptor
import android.util.Log
import com.vlesscardvpn.domain.AppSettings
import com.vlesscardvpn.domain.VlessConfig
import go.Seq
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import libv2ray.Libv2ray
import java.net.ServerSocket

/**
 * Controller for running the v2ray-core engine with tun2socks.
 */
class V2RayCoreEngine(private val context: Context) {
    private var controller: CoreController? = null
    private var isStarted = false

    fun start(
        config: VlessConfig,
        tunFd: ParcelFileDescriptor,
        settings: AppSettings = AppSettings()
    ): Int {
        stop()
        
        Seq.setContext(context.applicationContext)
        Libv2ray.initCoreEnv(context.filesDir.absolutePath, "")

        val socksPort = ServerSocket(0).use { it.localPort }
        val configJson = V2RayManager.generateConfig(config, socksPort, settings)

        val ctrl = Libv2ray.newCoreController(object : CoreCallbackHandler {
            override fun startup(): Long = 0
            override fun shutdown(): Long = 0
            override fun onEmitStatus(code: Long, message: String?): Long {
                Log.d("V2RayCoreEngine", "Status: $code, msg: $message")
                return 0
            }
        })

        // Start core and attach TUN file descriptor
        ctrl.startLoop(configJson, tunFd.fd.toLong())
        controller = ctrl
        isStarted = ctrl.isRunning
        return socksPort
    }

    fun isRunning(): Boolean = controller?.isRunning == true

    fun stop() {
        try {
            controller?.stopLoop()
        } catch (e: Exception) {
            Log.w("V2RayCoreEngine", "Error stopping v2ray loop", e)
        } finally {
            controller = null
            isStarted = false
        }
    }
}
