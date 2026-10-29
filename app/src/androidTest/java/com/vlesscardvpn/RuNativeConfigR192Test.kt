package com.vlesscardvpn

import androidx.test.platform.app.InstrumentationRegistry
import com.vlesscardvpn.core.XrayCore
import com.vlesscardvpn.model.*
import com.vlesscardvpn.xray.XrayConfigBuilder
import java.net.ServerSocket
import org.junit.Assert.*
import org.junit.Test

/** Native parser/start smoke only: no public upstream or full Android TUN measurement. */
class RuNativeConfigR192Test {
 @Test fun nativeXrayAcceptsScopedRuDnsAndRoutingOnOffTor() {
  XrayCore.init(InstrumentationRegistry.getInstrumentation().targetContext)
  val server=Server("reserved-fixture","vless","192.0.2.1",443,"00000000-0000-4000-8000-000000000001")
  for (settings in listOf(Settings(),Settings(ruWhitelist=false),Settings(ruAllDirect=true),Settings(ruAllDirect=true,alwaysVpn=listOf("example.ru")),Settings(ruAllDirect=true,torAll=true),Settings(torAll=true))) {
   val port=ServerSocket(0).use {it.localPort}
   val core=XrayCore.Instance("scoped-ru-r192")
   try {core.start(XrayConfigBuilder.vpnConfig(listOf(server to null),settings.copy(socksPort=port),null));assertTrue(core.running)}
   finally {core.stop()}
  }
 }
}
