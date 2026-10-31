package com.vlesscardvpn.core

import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import okio.Timeout
import java.net.*
import java.io.DataInputStream
import java.util.concurrent.*

class ServiceTransportR221Test {
    private class Reply(private val req:Request,private val data:String):Call {
        override fun request()=req
        override fun execute()=Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
            .header("Content-Type","text/html").body(data.toResponseBody()).build()
        override fun enqueue(responseCallback:Callback) {throw UnsupportedOperationException()}
        override fun cancel() {}
        override fun isExecuted()=false
        override fun isCanceled()=false
        override fun timeout()=Timeout()
        override fun clone():Call=Reply(req,data)
    }
    private val media="https://rr1.googlevideo.com/videoplayback?sig=test"
    private fun page(status:String="OK")="""ytInitialPlayerResponse={"playabilityStatus":{"status":"$status"},"streamingData":{"formats":[{"mimeType":"video/mp4","url":"$media"}]}}"""
    @Test fun playerAfterOldOneMiBLimitIsFoundWithinBoundedPage() {
        val html=" ".repeat(1100000)+page()
        assertEquals(media,YouTubeMediaProbe.discoverDetailed {Reply(it,html)}.url)
    }
    @Test fun oversizedPlayerStillDoesNotPass() {
        assertNull(YouTubeMediaProbe.playerJson(" ".repeat((2*1048576))+page()))
    }
    @Test fun loginChallengeIsExplicitAndDoesNotDownloadVideo() {
        var requests=0
        val r=YouTubeMediaProbe.check {requests++;Reply(it,page("LOGIN_REQUIRED"))}
        assertNull(r.ok);assertEquals("YT_LOGIN_REQUIRED",r.reason);assertEquals(1,requests)
    }
    @Test fun unavailableVideoIsNotProofOfDeadRoute() {
        val r=YouTubeMediaProbe.check {Reply(it,page("UNPLAYABLE"))}
        assertNull(r.ok);assertEquals("YT_UNPLAYABLE",r.reason)
    }
    @Test fun helloNeedsProtocolAndPositiveHeartbeatInterval() {
        assertTrue(DiscordRouteProbe.validHello("""{"op":10,"d":{"heartbeat_interval":45000}}"""))
        for(s in listOf("{}","<html>","""{"op":10}""","""{"op":11,"d":{"heartbeat_interval":45000}}""","""{"op":10,"d":{"heartbeat_interval":0}}""")) assertFalse(s,DiscordRouteProbe.validHello(s))
        assertFalse(DiscordRouteProbe.validHello(" ".repeat(65537)))
    }
    private fun gateway(frame:String?,httpOk:Boolean=false,block:(String)->Unit) {
        ServerSocket(0,1,InetAddress.getByName("127.0.0.1")).use {server->
            val pool=Executors.newSingleThreadExecutor()
            val job=pool.submit {
                server.accept().use {s->
                    s.soTimeout=2000
                    val reader=s.getInputStream().bufferedReader();val lines=mutableListOf<String>()
                    while(true) {val line=reader.readLine() ?: break;if(line.isEmpty()) break;lines+=line}
                    val key=lines.first {it.startsWith("Sec-WebSocket-Key:",true)}.substringAfter(':').trim()
                    val accept=java.util.Base64.getEncoder().encodeToString(java.security.MessageDigest.getInstance("SHA-1").digest((key+"258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray()))
                    val out=s.getOutputStream()
                    val headers=if(httpOk) "HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n" else "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: $accept\r\n\r\n"
                    out.write(headers.toByteArray());out.flush()
                    if(frame!=null) {val bytes=frame.toByteArray();out.write(byteArrayOf(0x81.toByte(),bytes.size.toByte())+bytes);out.flush()}
                    try {while(s.getInputStream().read()!=-1) {}} catch(_:java.io.IOException) {}
                }
            }
            try {block("ws://127.0.0.1:${server.localPort}/");job.get(3,TimeUnit.SECONDS)}
            finally {pool.shutdownNow()}
        }
    }
    private fun withClient(block:(OkHttpClient)->Unit) {
        val client=OkHttpClient.Builder().proxy(Proxy.NO_PROXY).build()
        try {block(client)} finally {client.dispatcher.cancelAll();client.connectionPool.evictAll();client.dispatcher.executorService.shutdownNow()}
    }
    @Test fun realWebsocketHelloPassesWithoutIdentify()=withClient {client->
        gateway("""{"op":10,"d":{"heartbeat_interval":45000}}""") {url->assertEquals(true,DiscordRouteProbe.hello(client,url,1500).ok)}
    }
    @Test fun upgradeWithoutHelloCannotPass()=withClient {client->
        gateway(null) {url->val r=DiscordRouteProbe.hello(client,url,200);assertNull(r.ok);assertEquals("TIMEOUT",r.reason)}
    }
    @Test fun ordinaryHttpCannotPassGateway()=withClient {client->
        gateway(null,true) {url->assertNull(DiscordRouteProbe.hello(client,url,1000).ok)}
    }
    @Test fun foreignWebsocketMessageCannotPass()=withClient {client->
        gateway("""{"op":11,"d":null}""") {url->assertNull(DiscordRouteProbe.hello(client,url,1000).ok)}
    }
    @Test fun cancellationBeforeSocketCreationMakesNoConnection()=withClient {client->
        val r=DiscordRouteProbe.hello(client,"ws://127.0.0.1:1/",1000) {cancel->cancel()}
        assertNull(r.ok);assertEquals("CANCELLED",r.reason)
    }
    @Test fun udpTrainTraversesOneSocksAssociationAndKeepsControlOpen() {
        ServerSocket(0,1,InetAddress.getByName("127.0.0.1")).use {server->
            DatagramSocket(0,InetAddress.getByName("127.0.0.1")).use {relay->
                val pool=Executors.newSingleThreadExecutor()
                val task=pool.submit {
                    server.accept().use {s->
                        s.soTimeout=3000
                        val input=DataInputStream(s.getInputStream());val out=s.getOutputStream()
                        val greeting=ByteArray(3);input.readFully(greeting);assertArrayEquals(byteArrayOf(5,1,0),greeting)
                        out.write(byteArrayOf(5,0));out.flush()
                        val association=ByteArray(10);input.readFully(association);assertEquals(3,association[1].toInt())
                        out.write(byteArrayOf(5,0,0,1,127,0,0,1,(relay.localPort shr 8).toByte(),relay.localPort.toByte()));out.flush()
                        relay.soTimeout=3000
                        repeat(CallTransportPolicy.PACKETS) {
                            val packet=DatagramPacket(ByteArray(1200),1200);relay.receive(packet)
                            val payload=UdpCallProbe.socksUdpPayload(packet.data,packet.length)!!
                            assertEquals(20,payload.size);payload[0]=1
                            val response=UdpCallProbe.socksUdpFrame(UdpCallProbe.HOST,UdpCallProbe.PORT,payload)
                            relay.send(DatagramPacket(response,response.size,packet.address,packet.port))
                        }
                        assertEquals(-1,input.read())
                    }
                }
                try {
                    val t=UdpCallProbe.train(server.localPort,"","",4000,CallTransportPolicy.PACKETS,null)
                    assertEquals(20,t.sent);assertEquals(20,t.rttMs.size);assertTrue(t.complete)
                    task.get(5,TimeUnit.SECONDS)
                } finally {pool.shutdownNow()}
            }
        }
    }
    @Test fun labelsDoNotClaimFailureOrSuccessForChallenges() {
        assertTrue(ServiceProbeStatus.label(true,null,"YT_LOGIN_REQUIRED").contains("требует вход"))
        assertTrue(ServiceProbeStatus.label(true,null,"DISCORD_GATEWAY").contains("HELLO"))
    }
}
