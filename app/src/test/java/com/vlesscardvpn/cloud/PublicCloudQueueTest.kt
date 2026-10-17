package com.vlesscardvpn
import com.vlesscardvpn.core.*
import com.vlesscardvpn.model.Server
import org.junit.Assert.*
import org.junit.Test
class PublicCloudQueueTest {
    private fun node(i:Int)=Server("node$i","vless","host$i.example",443,"id$i")
    @Test fun cloudNotBuriedBehindThousandsOfUnmeasuredRoutes() {
        val list=(0..2000).map(::node)
        val got=ConnectPolicy.candidates(list,listOf(list[1900],list[1901]),emptySet()) { if(it==list[0])0 else 100 }
        assertEquals(list[0],got.first());assertEquals(8,got.size)
        assertTrue(got.take(3).containsAll(listOf(list[1900],list[1901])))
    }
    @Test fun absentOrCoolingCloudCannotJumpQueue() {
        val list=(0..20).map(::node)
        val got=ConnectPolicy.candidates(list,listOf(node(99),list[20]),emptySet(),{it==list[20]}) {0}
        assertFalse(got.contains(list[20]));assertFalse(got.contains(node(99)))
    }
    @Test fun preflightIncludesUnknownCloudBesideMeasuredLeader() {
        val list=(0..20).map(::node)
        val got=PublicCloudQueue.merge(list.take(12),list.takeLast(2),list,12)
        assertEquals(list[0],got.first());assertTrue(got.take(3).containsAll(list.takeLast(2)));assertEquals(12,got.size)
    }
    @Test fun boundedEmptyAndDuplicatesAreSafe() {
        val a=node(1)
        assertTrue(PublicCloudQueue.merge(listOf(a),listOf(a),listOf(a),0).isEmpty())
        assertEquals(listOf(a),PublicCloudQueue.merge(emptyList(),listOf(a,a),listOf(a),8))
    }
}
