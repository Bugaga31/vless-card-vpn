package com.vlesscardvpn.core

import com.vlesscardvpn.model.Server
import com.vlesscardvpn.model.Settings
import com.vlesscardvpn.core.AutoChainSelection.Layout

/** No mocked PASS. Drives actual Android Xray/DPI through the authenticated host fixture. */
object RuntimeChainE2E {
    suspend fun measure(settings:Settings,servers:List<Server>,set:DpiSet,network:String,epoch:Long,
                        generation:()->Long):AutoChainRunner.Measured? {
        val good=servers.single {it.name=="e2e-reality" && it.address=="10.0.2.2" && it.port==8443}
        val dead=servers.single {it.name=="e2e-dead" && it.address=="10.0.2.2" && it.port==8999}
        check(set.currentPort!=null)
        val baseline=AutoChainRunner.choice(dead,null,Layout.SERVER,settings)
        val dpi=AutoChainRunner.choice(good,null,Layout.DPI_SERVER,settings,
            set.current.toString()+"|"+settings.byeDpiSni,com.vlesscardvpn.xray.Masks.CURRENT_DPI)
        return AutoChainRunner.compare(listOf(baseline,dpi),settings,network,epoch,generation,android.os.SystemClock::elapsedRealtime,
            baseline.chain.id,set.currentPort,set.ports.toMap(),false)
    }
}
