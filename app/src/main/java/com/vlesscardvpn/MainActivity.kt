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
        importFrom(intent)
    }

    override fun onNewIntent(intent: android.content.Intent) { super.onNewIntent(intent); e2e(intent); importFrom(intent) }

    /** A tapped vless://… link or a text shared to the app: import it right away. */
    private fun importFrom(i: android.content.Intent?) {
        val text = when (i?.action) {
            android.content.Intent.ACTION_VIEW -> i.dataString
            android.content.Intent.ACTION_SEND -> i.getStringExtra(android.content.Intent.EXTRA_TEXT)
            else -> null
        } ?: return
        i?.action = null // not again on rotation
        android.widget.Toast.makeText(this, com.vlesscardvpn.core.Actions.importAny(text), android.widget.Toast.LENGTH_LONG).show()
    }

    /** Copied a link somewhere else and came back: offer to add it (once per copied text). Android 10+ reads the clipboard only with focus. */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) return
        val text = runCatching { (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager).primaryClip?.getItemAt(0)?.text?.toString() }.getOrNull() ?: return
        if (text.length > 200_000) return
        val prefs = getSharedPreferences("clip", MODE_PRIVATE)
        val h = text.hashCode()
        if (prefs.getInt("last", 0) == h) return
        prefs.edit().putInt("last", h).apply()
        val what = com.vlesscardvpn.core.Actions.clipSummary(text)
        if (what.isNotEmpty()) com.vlesscardvpn.core.Actions.clipOffer.value = text to what
    }

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
