package com.vlesscardvpn.vpn

import android.content.Intent
import android.net.VpnService
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.vlesscardvpn.MainActivity
import com.vlesscardvpn.core.Tunnel

/** Quick settings tile: one tap to connect / disconnect. */
class ToggleTileService : TileService() {
    override fun onStartListening() { refresh() }
    override fun onClick() {
        val on = Tunnel.status.value.state.let { it == Tunnel.State.CONNECTED || it == Tunnel.State.CONNECTING }
        if (on) TunnelService.stop(this)
        else if (VpnService.prepare(this) == null) TunnelService.start(this)
        else {
            val i = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (android.os.Build.VERSION.SDK_INT >= 34)
                startActivityAndCollapse(android.app.PendingIntent.getActivity(this, 0, i, android.app.PendingIntent.FLAG_IMMUTABLE))
            else @Suppress("DEPRECATION") startActivityAndCollapse(i)
        }
        refresh()
    }
    private fun refresh() {
        val t = qsTile ?: return
        t.state = if (Tunnel.status.value.state == Tunnel.State.CONNECTED) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        t.updateTile()
    }
}
