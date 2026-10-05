package com.vlesscardvpn

import android.app.Application
import com.vlesscardvpn.core.Actions
import com.vlesscardvpn.core.Store
import com.vlesscardvpn.core.XrayCore

class VlessApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Store.init(this)
        Actions.init(this)
        Thread({ runCatching { XrayCore.init(this) } }, "xray-init").start()
    }
}
