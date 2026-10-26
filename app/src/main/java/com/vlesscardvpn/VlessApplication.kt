package com.vlesscardvpn

import android.app.Application
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import com.vlesscardvpn.core.Actions
import com.vlesscardvpn.core.Store

class VlessApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // The native LLM process must never initialize VPN, Store, jobs or network watchers.
        val process = runCatching { if (android.os.Build.VERSION.SDK_INT >= 28) getProcessName() else java.io.File("/proc/self/cmdline").readText().substringBefore('\u0000') }.getOrDefault("")
        if (process != packageName) return
        Store.init(this)
        Actions.init(this)
        com.vlesscardvpn.core.PhonePower.start(this)
        runCatching { com.vlesscardvpn.core.DeviceCoach.restoreBackground(this) }
        com.vlesscardvpn.core.Tester.installAuthenticator()
        // widget + quick tile follow the VPN state
        val app = this
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main).launch {
            com.vlesscardvpn.core.Tunnel.status.map { Triple(it.state, it.checkOk, it.route) }.distinctUntilChanged().collect {
                com.vlesscardvpn.ai.LocalAutoControl.invalidate()
                com.vlesscardvpn.vpn.VpnWidget.refresh(app)
                runCatching { android.service.quicksettings.TileService.requestListeningState(app, android.content.ComponentName(app, com.vlesscardvpn.vpn.ToggleTileService::class.java)) }
            }
        }
    }
}
