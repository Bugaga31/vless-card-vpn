package com.vlesscardvpn
import com.vlesscardvpn.core.*
import org.junit.Assert.*
import org.junit.Test
class RouteStabilityR206Test {
 private fun good()=LinkStabilityPolicy.Sample(true,100)
 private fun bad()=LinkStabilityPolicy.Sample(false,0)
 private fun quarantine(m:RouteStabilityMemory,base:Long=1000):Long {for(i in 0..4)m.record("wifi","a","m",bad(),base+i,1000);return base+4}
 @Test fun singleFailureDoesNotBan(){val m=RouteStabilityMemory();m.record("wifi","a","m",bad(),1000,1000);repeat(3){m.record("wifi","a","m",good(),1001L+it,1000)};assertFalse(m.cooling("wifi","a","m",1005))}
 @Test fun repeatedFailuresQuarantined(){val m=RouteStabilityMemory();val t=quarantine(m);assertTrue(m.cooling("wifi","a","m",t))}
 @Test fun networkSeparate(){val m=RouteStabilityMemory();quarantine(m);assertFalse(m.cooling("mobile","a","m",1010))}
 @Test fun serverSeparate(){val m=RouteStabilityMemory();quarantine(m);assertFalse(m.cooling("wifi","b","m",1010))}
 @Test fun maskSeparate(){val m=RouteStabilityMemory();quarantine(m);assertFalse(m.cooling("wifi","a","other",1010))}
 @Test fun expiresTwoMinutes(){val m=RouteStabilityMemory();val t=quarantine(m);assertFalse(m.cooling("wifi","a","m",t+120000))}
 @Test fun singleGoodDoesNotClearQuarantine(){val m=RouteStabilityMemory();val t=quarantine(m);m.record("wifi","a","m",good(),t+1,1000);assertTrue(m.cooling("wifi","a","m",t+2))}
 @Test fun unknownDoesNotBan(){val m=RouteStabilityMemory();repeat(8){m.record("wifi","a","m",LinkStabilityPolicy.Sample(null,0),1000L+it,1000)};assertFalse(m.cooling("wifi","a","m",1010))}
 @Test fun stableSlowPermittedWithinUserGate(){val m=RouteStabilityMemory();repeat(8){m.record("wifi","a","m",LinkStabilityPolicy.Sample(true,900),1000L+it,1000)};assertFalse(m.cooling("wifi","a","m",1010))}
 @Test fun repeatedVarianceQuarantined(){val m=RouteStabilityMemory();repeat(7){m.record("wifi","a","m",LinkStabilityPolicy.Sample(true,if(it%2==0)100 else 400),1000L+it,1000)};assertTrue(m.cooling("wifi","a","m",1010))}
 @Test fun clockRollbackClears(){val m=RouteStabilityMemory();quarantine(m);assertFalse(m.cooling("wifi","a","m",100))}
 @Test fun boundedMap(){val m=RouteStabilityMemory(1);quarantine(m);m.record("wifi","b","m",good(),1010,1000);assertFalse(m.cooling("wifi","a","m",1011))}
 @Test fun ordinarySingleHopOnly(){assertTrue(RouteStabilityMemory.mayAttribute(true,1,PowerPolicy.Plan()));assertFalse(RouteStabilityMemory.mayAttribute(true,2,PowerPolicy.Plan()));assertFalse(RouteStabilityMemory.mayAttribute(false,1,PowerPolicy.Plan()))}
 @Test fun phoneHeatNotServerFailure(){assertFalse(RouteStabilityMemory.mayAttribute(true,1,PowerPolicy.Plan(memory=PowerPolicy.Memory(hot=true))))}
 @Test fun phonePressureNotServerFailure(){assertFalse(RouteStabilityMemory.mayAttribute(true,1,PowerPolicy.Plan(allowLight=false,allowReserve=false)))}
 @Test fun voiceNotInterruptedForScore(){assertFalse(RouteStabilityMemory.mayAttribute(true,1,PowerPolicy.Plan(voiceCallActive=true)))}
 @Test fun activeDownloadNotServerFailure(){assertFalse(RouteStabilityMemory.mayAttribute(true,1,PowerPolicy.Plan(deferTraffic=true)))}
 @Test fun ecoStillSupportsQuality(){assertTrue(RouteStabilityMemory.mayAttribute(true,1,PowerPolicy.Plan(allowLight=false,allowReserve=true)))}
}
