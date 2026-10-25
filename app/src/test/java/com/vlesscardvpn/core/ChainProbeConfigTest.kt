package com.vlesscardvpn.core

import com.vlesscardvpn.core.AutoChainSelection.Chain
import com.vlesscardvpn.core.AutoChainSelection.Layout
import com.vlesscardvpn.xray.ChainProbeConfig
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ChainProbeConfigTest {
    private fun c(layout:Layout)=Chain(layout, serverKey=if(layout.server)"fixture" else "",
        torProfileKey=if(layout.tor)"bridge1" else "",dpiKey=if(layout.dpi)"fixed1" else "")
    private fun server()=JSONObject("""{"tag":"old","protocol":"socks","settings":{"servers":[{"address":"127.0.0.1","port":19051}]}}""")
    private fun config(layout:Layout)=JSONObject(ChainProbeConfig.build(c(layout),19050,if(layout.server)server() else null,19052,19053))
    private fun rejects(block:()->Unit) {try {block();fail("Must reject incompatible configuration")}catch(_:IllegalArgumentException){}}
    @Test fun `Tor to server dials server through Tor not Tor localhost through server`() {
        val x=config(Layout.TOR_SERVER);val out=x.getJSONArray("outbounds")
        assertEquals("chain-tor",out.getJSONObject(0).getJSONObject("streamSettings").getJSONObject("sockopt").getString("dialerProxy"))
        assertEquals(19052,out.getJSONObject(1).getJSONObject("settings").getJSONArray("servers").getJSONObject(0).getInt("port"))
    }
    @Test fun `DPI to server dials configured DPI listener`() {
        val out=config(Layout.DPI_SERVER).getJSONArray("outbounds")
        assertEquals("chain-dpi",out.getJSONObject(0).getJSONObject("streamSettings").getJSONObject("sockopt").getString("dialerProxy"))
        assertEquals(19053,out.getJSONObject(1).getJSONObject("settings").getJSONArray("servers").getJSONObject(0).getInt("port"))
    }
    @Test fun `source outbound is not mutated`() {val s=server();val before=s.toString();ChainProbeConfig.build(c(Layout.TOR_SERVER),19050,s,19052);assertEquals(before,s.toString())}
    @Test fun `endpoint and credentials unchanged`() {val s=server();val x=JSONObject(ChainProbeConfig.build(c(Layout.TOR_SERVER),19050,s,19052));assertEquals(s.getJSONObject("settings").toString(),x.getJSONArray("outbounds").getJSONObject(0).getJSONObject("settings").toString())}
    @Test fun `no freedom outbound or direct fallback`() {ChainProbeConfig.implemented.forEach {layout -> val x=config(layout);val out=x.getJSONArray("outbounds");for(i in 0 until out.length())assertNotEquals("freedom",out.getJSONObject(i).getString("protocol"));assertFalse(x.getJSONArray("inbounds").getJSONObject(0).getJSONObject("settings").getBoolean("udp"))}}
    @Test fun `reverse and triple layouts rejected not pretended`() { (Layout.values().toSet()-ChainProbeConfig.implemented).forEach {layout -> rejects {config(layout)} } }
    @Test fun `loopback listener ports cannot collide`() {rejects {ChainProbeConfig.build(c(Layout.TOR),19050,torPort=19050)}}
    @Test fun `missing Tor listener cannot become direct route`() {rejects {ChainProbeConfig.build(c(Layout.TOR),19050)}}
    @Test fun `missing DPI listener cannot become direct route`() {rejects {ChainProbeConfig.build(c(Layout.DPI_SERVER),19050,server())}}
    @Test fun `existing proxy cannot be overwritten`() {val s=server().put("streamSettings",JSONObject().put("sockopt",JSONObject().put("dialerProxy","old")));rejects {ChainProbeConfig.build(c(Layout.TOR_SERVER),19050,s,19052)}}
    @Test fun `raw freedom server cannot masquerade as VPN`() {rejects {ChainProbeConfig.build(c(Layout.SERVER),19050,JSONObject().put("protocol","freedom"))}}
    @Test fun `UDP server cannot masquerade as TCP chain`() {rejects {ChainProbeConfig.build(c(Layout.TOR_SERVER).copy(tcpServer=false),19050,server(),19052)}}
    @Test fun `config fixtures exported for real Xray smoke`() {
        val dir=java.io.File("/tmp/chain-probe-configs");dir.mkdirs()
        ChainProbeConfig.implemented.forEach {layout -> java.io.File(dir,"${layout.name}.json").writeText(config(layout).toString(2))}
    }
}
