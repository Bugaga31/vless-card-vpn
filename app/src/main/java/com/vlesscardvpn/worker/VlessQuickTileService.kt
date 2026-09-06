package com.vlesscardvpn.worker

import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi
import com.vlesscardvpn.MainActivity
import com.vlesscardvpn.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@RequiresApi(Build.VERSION_CODES.N)
class VlessQuickTileService : TileService() {

    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private var statsJob: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        updateTileState(VlessVpnService.vpnStats.value.status)
        statsJob = scope.launch {
            VlessVpnService.vpnStats.collectLatest { stats ->
                updateTileState(stats.status)
            }
        }
    }

    override fun onStopListening() {
        super.onStopListening()
        statsJob?.cancel()
    }

    override fun onClick() {
        super.onClick()
        val currentStatus = VlessVpnService.vpnStats.value.status
        if (currentStatus == VpnStatus.CONNECTED || currentStatus == VpnStatus.CONNECTING) {
            VlessVpnService.stopVpn(this)
        } else {
            // Launch MainActivity to let user trigger connect or auto-connect
            val intent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("EXTRA_AUTO_CONNECT", true)
            }
            startActivityAndCollapse(intent)
        }
    }

    private fun updateTileState(status: VpnStatus) {
        val tile = qsTile ?: return
        when (status) {
            VpnStatus.CONNECTED -> {
                tile.state = Tile.STATE_ACTIVE
                tile.label = "VLESS: ON"
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.subtitle = "Protected"
                }
            }
            VpnStatus.CONNECTING -> {
                tile.state = Tile.STATE_ACTIVE
                tile.label = "VLESS: ..."
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.subtitle = "Connecting"
                }
            }
            else -> {
                tile.state = Tile.STATE_INACTIVE
                tile.label = "VLESS: OFF"
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.subtitle = "Tap to launch"
                }
            }
        }
        tile.updateTile()
    }
}
