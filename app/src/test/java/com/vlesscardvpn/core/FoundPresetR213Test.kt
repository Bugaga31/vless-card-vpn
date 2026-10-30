package com.vlesscardvpn.core
import org.junit.Assert.*
import org.junit.Test
class FoundPresetR213Test {
 @Test fun strictForegroundBudgetHas800GatePlus100SchedulingSlack() {
  assertEquals(800,AutoLatencyPolicy.MAX_MS)
  assertEquals(900L,StrictProbeBudget.budget(1800,true,true,false,false,false,1))
  val p=Probe(850,null,true,tgOk=true,tgMs=700)
  assertFalse(AutoLatencyPolicy.accepts(p))
 }
 @Test fun searchStatisticsNameTheActual800Gate() {
  val s=AutoSearchStatistics();s.start();s.observe(true,578,true,578);s.start();s.observe(true,801,true,578)
  assertEquals(1,s.snapshot()["ready"]);assertEquals(1,s.snapshot()["over_800ms"])
  assertFalse(s.snapshot().containsKey("over_1000ms"))
 }
}
