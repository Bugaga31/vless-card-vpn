package com.vlesscardvpn
import com.vlesscardvpn.core.*
import org.junit.Assert.*
import org.junit.Test
import java.net.*
import java.io.DataInputStream
import java.util.concurrent.TimeUnit
class CallQualityR206Test {
 private fun assess(rtts:List<Int> = List(20){100},sent:Int=20,complete:Boolean=true,max:Int=1000)=CallTransportPolicy.assess(sent,rtts,complete,max)
 @Test fun trainTwenty(){assertEquals(20,CallTransportPolicy.PACKETS)}
 @Test fun paceFiftyMs(){assertEquals(50L,CallTransportPolicy.INTERVAL_MS)}
 @Test fun cleanPass(){assertEquals(CallTransportPolicy.Grade.PASS,assess().grade)}
 @Test fun noUdp(){assertEquals(CallTransportPolicy.Grade.NO_UDP,assess(emptyList()).grade)}
 @Test fun incompleteUnknown(){assertEquals(CallTransportPolicy.Grade.UNKNOWN,assess(complete=false).grade)}
 @Test fun shortTrainUnknown(){assertEquals(CallTransportPolicy.Grade.UNKNOWN,assess(List(10){100},10).grade)}
 @Test fun oneLostCaution(){assertEquals(CallTransportPolicy.Grade.CAUTION,assess(List(19){100}).grade)}
 @Test fun twoLostNotReady(){assertEquals(CallTransportPolicy.Grade.LOSS,assess(List(18){100}).grade)}
 @Test fun delayedSpeech(){assertEquals(CallTransportPolicy.Grade.SLOW,assess(List(20){600}).grade)}
 @Test fun acceptableAutoNotSameAsGoodVoice(){assertEquals(CallTransportPolicy.Grade.SLOW,assess(List(20){1000}).grade)}
 @Test fun customLowerCeiling(){assertEquals(CallTransportPolicy.Grade.SLOW,assess(List(20){250},max=200).grade)}
 @Test fun voiceCaution(){assertEquals(CallTransportPolicy.Grade.CAUTION,assess(List(20){400}).grade)}
 @Test fun varyingRttUnstable(){assertEquals(CallTransportPolicy.Grade.UNSTABLE,assess(List(10){100}+List(10){200}).grade)}
 @Test fun duplicateCountsBounded(){assertEquals(20,assess(List(100){100}).received)}
 @Test fun invalidRttNotSuccess(){assertEquals(CallTransportPolicy.Grade.NO_UDP,assess(List(20){0}).grade)}
 @Test fun p95NotSingleFastest(){val s=assess(List(2){1}+List(18){500});assertEquals(500,s.p95Ms)}
 @Test fun labelDoesNotClaimRealCall(){assertFalse(CallTransportPolicy.label(assess()).contains("звонок работает"))}
 @Test fun helloOpcode(){assertEquals(10,DiscordGatewayProbe.opcode("{\"op\":10,\"d\":{\"heartbeat_interval\":45000}}"))}
 @Test fun ackOpcode(){assertEquals(11,DiscordGatewayProbe.opcode("{\"op\":11,\"d\":null}"))}
 @Test fun malformedGatewayUnknown(){assertNull(DiscordGatewayProbe.opcode("bad"));assertNull(DiscordGatewayProbe.opcode("{}"))}
 @Test fun oversizedGatewayUnknown(){assertNull(DiscordGatewayProbe.opcode(" ".repeat(17000)))}
 private class Relay(val dropEvery:Int=0,val duplicate:Boolean=false):AutoCloseable {
  val tcp=ServerSocket(0,1,InetAddress.getLoopbackAddress())
  val udp=DatagramSocket(0,InetAddress.getLoopbackAddress())
  @Volatile var control:Socket?=null
  @Volatile var count=0
  val worker=Thread {
   try {
    control=tcp.accept();val input=DataInputStream(control!!.getInputStream());val output=control!!.getOutputStream()
    val greet=ByteArray(3);input.readFully(greet);output.write(byteArrayOf(5,0));output.flush()
    val associate=ByteArray(10);input.readFully(associate)
    output.write(byteArrayOf(5,0,0,1,127,0,0,1,(udp.localPort shr 8).toByte(),udp.localPort.toByte()));output.flush()
    udp.soTimeout=4500
    while(!udp.isClosed) {
     val buffer=ByteArray(1500);val p=DatagramPacket(buffer,buffer.size);udp.receive(p)
     val body=UdpCallProbe.socksUdpPayload(buffer,p.length) ?: continue
     count++
     if(dropEvery>0 && count%dropEvery==0) continue
     body[0]=1;body[1]=1
     val reply=UdpCallProbe.socksUdpFrame(UdpCallProbe.HOST,UdpCallProbe.PORT,body)
     udp.send(DatagramPacket(reply,reply.size,p.address,p.port));if(duplicate)udp.send(DatagramPacket(reply,reply.size,p.address,p.port))
    }
   } catch(_:Exception) { }
  }.apply {isDaemon=true;start()}
  override fun close(){runCatching {control?.close()};udp.close();tcp.close();worker.join(500)}
 }
 @Test fun realSocksUdpTrainUsesOneAssociation(){Relay().use {r->val x=UdpCallProbe.voiceTrain(r.tcp.localPort);assertEquals(20,x.sent);assertEquals(20,x.rttMs.size);assertTrue(x.complete);assertEquals(20,r.count)}}
 @Test fun realSocksUdpDropsNotPass(){Relay(4).use {r->val x=UdpCallProbe.voiceTrain(r.tcp.localPort);assertEquals(20,x.sent);assertEquals(15,x.rttMs.size);assertTrue(x.complete);assertEquals(CallTransportPolicy.Grade.LOSS,CallTransportPolicy.assess(x.sent,x.rttMs,x.complete,1000).grade)}}
 @Test fun duplicateResponsesDoNotInflateSuccess(){Relay(duplicate=true).use {r->val x=UdpCallProbe.voiceTrain(r.tcp.localPort);assertEquals(20,x.rttMs.size);assertEquals(20,x.sent)}}
 @Test fun cancellationNoFalseQuality(){val x=UdpCallProbe.voiceTrain(12345,onCancel={it()});assertFalse(x.complete);assertEquals(0,x.sent)}
}
