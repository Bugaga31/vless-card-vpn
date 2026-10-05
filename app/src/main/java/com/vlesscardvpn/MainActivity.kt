package com.vlesscardvpn

import android.Manifest
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import com.vlesscardvpn.ui.AppUi
import com.vlesscardvpn.vpn.TunnelService

class MainActivity : ComponentActivity() {
    private val vpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == RESULT_OK) TunnelService.start(this)
    }
    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    fun connect() {
        val intent = VpnService.prepare(this)
        if (intent != null) vpnPermission.launch(intent) else TunnelService.start(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        setContent { AppUi(onConnect = { connect() }, onDisconnect = { TunnelService.stop(this) }) }
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
        if (i.getBooleanExtra("e2e_masks", false)) com.vlesscardvpn.core.Actions.findMasks(com.vlesscardvpn.core.Store.state.value.servers)
    }
}
