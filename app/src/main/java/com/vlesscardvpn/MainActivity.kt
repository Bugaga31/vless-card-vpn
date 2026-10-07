package com.vlesscardvpn

import android.Manifest
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.launch
import com.vlesscardvpn.ui.AppUi
import com.vlesscardvpn.vpn.TunnelService

class MainActivity : ComponentActivity() {
    private val vpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == RESULT_OK) start()
    }

    /** Auto mode prepares servers/masks/DPI first (Actions.autoConnect), the other modes connect right away. */
    private fun start() {
        if (com.vlesscardvpn.core.Store.state.value.settings.mode == com.vlesscardvpn.model.Mode.AUTO)
            com.vlesscardvpn.core.Actions.autoConnect { TunnelService.start(this) }
        else TunnelService.start(this)
    }
    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    fun connect() {
        // Proxy mode has no TUN: no VPN permission needed.
        if (com.vlesscardvpn.core.Store.state.value.settings.proxyOnly) { start(); return }
        val intent = VpnService.prepare(this)
        if (intent != null) vpnPermission.launch(intent) else start()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        com.vlesscardvpn.core.Actions.init(this)
        if (!BuildConfig.DEBUG) com.vlesscardvpn.core.Actions.warmup() // background: subscriptions, tests, masks — ready before «Подключить»
        setContent { AppUi(onConnect = { connect() }, onDisconnect = { com.vlesscardvpn.core.Actions.cancelPrepare(); TunnelService.stop(this) }) }
        e2e(intent)
    }

    override fun onNewIntent(intent: android.content.Intent) { super.onNewIntent(intent); e2e(intent) }

    /** Debug builds only: hooks for the emulator end-to-end check (tools/e2e-android.sh). */
    private fun e2e(i: android.content.Intent?) {
        if (!BuildConfig.DEBUG || i == null) return
        if (i.getBooleanExtra("e2e_reload", false)) com.vlesscardvpn.core.Store.reload(this)
        if (i.getBooleanExtra("e2e_connect", false)) connect()
        if (i.getBooleanExtra("e2e_disconnect", false)) TunnelService.stop(this)
        if (i.getBooleanExtra("e2e_test", false)) com.vlesscardvpn.core.Actions.testAll()
        if (i.getBooleanExtra("e2e_dpi", false)) com.vlesscardvpn.core.Actions.findDpi()
        if (i.getBooleanExtra("e2e_optimize", false)) kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            android.util.Log.i("E2E", "optimize result=${runCatching { com.vlesscardvpn.core.Actions.optimize() }.getOrElse { "error " + it.message }}")
        }
        if (i.getBooleanExtra("e2e_warp", false)) com.vlesscardvpn.core.Actions.setupWarp()
        if (i.getBooleanExtra("e2e_masks", false)) com.vlesscardvpn.core.Actions.findMasks(com.vlesscardvpn.core.Store.state.value.servers)
    }
}
