package com.vlesscardvpn.tor
import com.vlesscardvpn.model.Settings
import com.vlesscardvpn.core.AutoCheckProfile
object TorFoundPolicy {
    fun blocker(s:Settings):String? {
        if(!s.torAll) return null
        if(AutoCheckProfile.of(s.autoChecks).voice)
            return "Tor не переносит UDP. Отключите выбранную проверку UDP для звонков в настройках Авто; автоматически она не отключается."
        val runtime=TorConnectionPolicy.runtime(s)
        if(runtime.torBridge !in setOf("obfs4","webtunnel") || runCatching {TorBridgeCatalog.selected(runtime.torBridgeLines,runtime.torBridge)}.getOrNull().isNullOrEmpty())
            return "Для Tor нужны сохранённые действительные мосты obfs4/WebTunnel. Добавьте мосты в настройках Tor; прямого обхода нет."
        return null
    }
    fun key(s:Settings)=com.vlesscardvpn.core.AutoChainRunner.digest(listOf(s.torAll,s.torBridge,s.torBridgeLines,s.torExitCountries).joinToString("|"))
}
