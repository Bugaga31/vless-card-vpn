package com.vlesscardvpn.core
import org.junit.Assert.*
import org.junit.Test
class FoundPresetR213Test {
 @Test fun strictForegroundBudgetHas3000GatePlus100SchedulingSlack() {
  assertEquals(3000,AutoLatencyPolicy.MAX_MS)
  assertEquals(3100L,StrictProbeBudget.budget(4500,true,true,false,false,false,1))
  val p=Probe(3050,null,true,tgOk=true,tgMs=700)
  assertFalse(AutoLatencyPolicy.accepts(p))
 }
 @Test fun searchStatisticsNameTheActual3000Gate() {
  val s=AutoSearchStatistics();s.start();s.observe(true,578,true,578);s.start();s.observe(true,3001,true,578)
  assertEquals(1,s.snapshot()["ready"]);assertEquals(1,s.snapshot()["over_3000ms"])
  assertFalse(s.snapshot().containsKey("over_1000ms"))
 }
}
