package com.vlesscardvpn.core
import com.vlesscardvpn.model.Server
import com.vlesscardvpn.model.Settings
/** No fixture driver in release; caller is additionally removed by BuildConfig.DEBUG. */
object RuntimeChainE2E {
    @Suppress("UNUSED_PARAMETER")
    suspend fun measure(settings:Settings,servers:List<Server>,set:DpiSet,network:String,epoch:Long,
                        generation:()->Long):AutoChainRunner.Measured? = null
}
