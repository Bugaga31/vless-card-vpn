package com.vlesscardvpn.core

import io.nekohasekai.libbox.ConnectionOwner
import io.nekohasekai.libbox.SystemProxyStatus

/** Non-null fallback values required by the gomobile/libbox callback contract. */
internal object NativeCallbackValues {
    fun disabledSystemProxy(): SystemProxyStatus = SystemProxyStatus().apply {
        available = false
        enabled = false
    }

    fun unknownConnectionOwner(): ConnectionOwner = ConnectionOwner().apply {
        userId = -1
        userName = ""
        processPath = ""
        androidPackageName = ""
    }
}
