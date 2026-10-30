package com.vlesscardvpn.core

import com.vlesscardvpn.model.Server
import com.vlesscardvpn.model.ServerState
import org.junit.Assert.*
import org.junit.Test

class FlyRokiR216Test {
    @Test fun embeddedGraphHasPinnedProvenanceAndExpectedDimensions() {
        assertEquals(516,FlyReservoir.NODES);assertEquals(11517,FlyReservoir.EDGES)
        assertEquals("b3f94ee800f9555cb02f7c397c183af7e24e11ac5fb735e871849de997dd5b84",FlyReservoir.GRAPH_SHA256)
        assertEquals(22,FlyReservoir.encode(doubleArrayOf(1.0,0.0,0.0,0.0,0.0)).size)
    }
    @Test fun pythonKotlinRealGraphEncoderParity1() {
        assertArrayEquals(doubleArrayOf(1.0,1.0,0.01,0.289,1.0,0.8,-0.941208543726384,-0.9412085437263841,-0.9412085437263841,-0.9412085437263842,-0.9412085437263841,-0.9412085437263841,-0.9412085437263842,-0.9412085437263841,-0.9412085437263841,-0.9412085437263842,-0.9412085437263841,-0.9412085437263841,-0.9412085437263841,-0.9412085437263841,-0.9412085437263841,-0.9412085437263841),FlyReservoir.encode(doubleArrayOf(1.0,0.01,0.289,1.0,0.8)),1e-10)
    }
    @Test fun pythonKotlinRealGraphEncoderParity2() {
        assertArrayEquals(doubleArrayOf(1.0,1.0,0.8,0.9,0.0,0.1,-0.2123023851817808,-0.2123023851817808,-0.2123023851817808,-0.2123023851817808,-0.2123023851817808,-0.2123023851817808,-0.2123023851817808,-0.2123023851817808,-0.2123023851817808,-0.2123023851817808,-0.2123023851817808,-0.2123023851817808,-0.2123023851817808,-0.2123023851817808,-0.2123023851817808,-0.2123023851817808),FlyReservoir.encode(doubleArrayOf(1.0,0.8,0.9,0.0,0.1)),1e-10)
    }
    @Test fun pythonKotlinRealGraphEncoderParity3() {
        assertArrayEquals(doubleArrayOf(1.0,1.0,0.0,0.0,0.0,0.0,-0.9133047491052764,-0.9133047491052764,-0.9133047491052764,-0.9133047491052764,-0.9133047491052764,-0.9133047491052764,-0.9133047491052764,-0.9133047491052764,-0.9133047491052764,-0.9133047491052764,-0.9133047491052764,-0.9133047491052764,-0.9133047491052764,-0.9133047491052764,-0.9133047491052764,-0.9133047491052764),FlyReservoir.encode(doubleArrayOf(1.0,0.0,0.0,0.0,0.0)),1e-10)
    }
    @Test fun reservoirResetsForEveryCandidateAndHasNoActivityLeak() {
        val input=doubleArrayOf(1.0,.01,.289,1.0,.8);val first=FlyReservoir.encode(input)
        FlyReservoir.encode(doubleArrayOf(1.0,.8,.9,0.0,.1))
        assertArrayEquals(first,FlyReservoir.encode(input),0.0)
    }
    @Test fun invalidAndUnboundedInputsAreRejected() {
        for(input in listOf(doubleArrayOf(1.0),doubleArrayOf(Double.NaN,0.0,0.0,0.0,0.0),doubleArrayOf(1.0,2.0,0.0,0.0,0.0))) {
            try {FlyReservoir.encode(input);fail("Invalid model input accepted")} catch(expected:IllegalArgumentException) {}
        }
    }
    @Test fun syntheticReadoutIsFiniteAndDefensivelyCopied() {
        val weights=FlyReservoir.initialWeights;assertEquals(22,weights.size);assertTrue(weights.all {it.isFinite()})
        val original=weights[0];weights[0]=Double.NaN;assertEquals(original,FlyReservoir.initialWeights[0],0.0)
    }
    @Test fun boundedRankingRetainsAllUnrankedCandidates() {
        val m=RokiRanker();repeat(8) {m.observe("a",ServerState(),true)}
        val routes=(1..80).map {Server("fixture$it","vless","192.0.2.$it",443,"fixture")}
        val sorted=m.order("a",routes){ServerState()}
        assertEquals(routes.size,sorted.size);assertEquals(routes.toSet(),sorted.toSet());assertEquals(routes.drop(64),sorted.drop(64))
    }
    @Test fun graphLearningCannotApproveUnknownSelectedService() {
        val m=RokiRanker();repeat(30) {m.observe("a",ServerState(realMs=50),true)}
        val profile=AutoCheckProfile.of(listOf("telegram","youtube","discord"))
        val unknown=Probe(50,true,null,tgOk=true,tgMs=50,discordOk=true)
        assertNull(profile.verdict(unknown,800));assertFalse(profile.accepts(unknown,800))
    }
    @Test fun graphLearningStillIgnoresCancelledAndUnknownOutcomes() {
        val m=RokiRanker();repeat(30) {m.observe("a",ServerState(),null);m.observe("a",ServerState(),true,true)}
        assertEquals(0,m.samples("a"))
    }
}
