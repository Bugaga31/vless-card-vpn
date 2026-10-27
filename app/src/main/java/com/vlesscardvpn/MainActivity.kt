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
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.Lifecycle
import com.vlesscardvpn.ui.AppUi
import com.vlesscardvpn.vpn.TunnelService

class MainActivity : ComponentActivity() {
    private val vpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == RESULT_OK) start()
    }

    /** Auto mode prepares servers/masks/DPI first (Actions.autoConnect), the other modes connect right away. */
    private fun start() {
        if (com.vlesscardvpn.tor.TorConnectionPolicy.needsAutoSearch(com.vlesscardvpn.core.Store.state.value.settings))
            com.vlesscardvpn.core.Actions.autoConnect { TunnelService.start(this) }
        else TunnelService.start(this)
    }
    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    fun connect() {
        // Stored legacy proxyOnly cannot bypass Android VPN consent.
        val intent = VpnService.prepare(this)
        if (intent != null) vpnPermission.launch(intent) else start()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        com.vlesscardvpn.core.Actions.init(this)
        if (!BuildConfig.DEBUG) com.vlesscardvpn.core.Actions.warmup() // background: subscriptions, tests, masks — ready before «Подключить»
        com.vlesscardvpn.core.Updater.schedule(this)
        com.vlesscardvpn.core.Updater.check(ctx = this)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    while (true) { runCatching { com.vlesscardvpn.core.DeviceCoach.sample(applicationContext) }; kotlinx.coroutines.delay(60000) }
                }
            }
        }
        setContent { AppUi(onConnect = { connect() }, onDisconnect = { com.vlesscardvpn.core.Actions.cancelPrepare(); TunnelService.stop(this) }) }
        e2e(intent, restored = savedInstanceState != null)
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
    private fun e2e(i: android.content.Intent?, restored: Boolean = false) {
        if (!BuildConfig.DEBUG || i == null) return
        // Consume before any side effect (including permission prompts/recreation).
        val commands = com.vlesscardvpn.core.E2ECommands.consume(
            listOf("e2e_chain", "e2e_reload", "e2e_connect", "e2e_disconnect", "e2e_test", "e2e_dpi", "e2e_optimize", "e2e_warp", "e2e_masks"), restored,
            { key -> i.getBooleanExtra(key, false) }, { key -> i.removeExtra(key); Unit })
        if (restored) android.util.Log.i("E2E", "fixture activity restored")
        if ("e2e_chain" in commands) startService(android.content.Intent(this,TunnelService::class.java).putExtra("e2e_chain_check",true))
        if ("e2e_reload" in commands) {
            com.vlesscardvpn.core.Store.reload(this)
            val st = com.vlesscardvpn.core.Store.state.value
            android.util.Log.i("E2E", "fixture state mode=${st.settings.mode} servers=${st.servers.size} selected=${st.selected.size}")
        }
        if ("e2e_connect" in commands) {
            android.util.Log.i("E2E", "fixture connect permission=${VpnService.prepare(this) != null}")
            connect()
        }
        if ("e2e_disconnect" in commands) TunnelService.stop(this)
        if ("e2e_test" in commands) com.vlesscardvpn.core.Actions.testAll()
        if ("e2e_dpi" in commands) com.vlesscardvpn.core.Actions.findDpi()
        if ("e2e_optimize" in commands) kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            // Test action waits for the previous bounded foreground action, rather than
            // treating optimize()'s busy early return as a failed route repair.
            val idle = kotlinx.coroutines.withTimeoutOrNull(60_000) {
                while (com.vlesscardvpn.core.Actions.running.value) kotlinx.coroutines.delay(100)
                true
            } == true
            val result = if (idle) runCatching { com.vlesscardvpn.core.Actions.optimize() }
                .getOrElse { "error " + it.javaClass.simpleName } else "error BusyTimeout"
            val selected = com.vlesscardvpn.core.Store.state.value.selected
            val dead = selected.any { it.name == "e2e-dead" }
            val good = selected.count { it.name in setOf("e2e-reality", "e2e-ws-tls", "e2e-xhttp") }
            android.util.Log.i("E2E", "optimize result=$result dead_selected=$dead good_selected=$good")
        }
        if ("e2e_warp" in commands) com.vlesscardvpn.core.Actions.setupWarp()
        if ("e2e_masks" in commands) com.vlesscardvpn.core.Actions.findMasks(com.vlesscardvpn.core.Store.state.value.servers)
    }
}
