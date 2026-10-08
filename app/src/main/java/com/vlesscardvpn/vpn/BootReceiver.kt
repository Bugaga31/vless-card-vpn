package com.vlesscardvpn.vpn

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import com.vlesscardvpn.core.Store

/** «Подключаться после перезагрузки»: the phone restarted → VPN back on (only if permission was already given). */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        runCatching {
            Store.init(context)
            val s = Store.state.value.settings
            if (s.autoStart && (s.proxyOnly || VpnService.prepare(context) == null)) TunnelService.start(context)
        }
    }
}
