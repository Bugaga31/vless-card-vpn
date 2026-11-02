package com.vlesscardvpn.core

/** Prequential audit: score the saved forecast BEFORE training on its future label.
 * Session counters only, no raw telemetry and no invented device training examples. */
class PhoneForecastAudit {
    var samples=0;private set
    private var total=0.0
    val brier:Double? get()=if(samples>0) total/samples else null
    fun observe(prediction:Double,hotLater:Boolean) {
        if(!prediction.isFinite() || prediction !in 0.0..1.0) return
        val error=prediction-(if(hotLater) 1.0 else 0.0)
        if(samples>=10000) {total*=.5;samples/=2}
        total+=error*error;samples++
    }
}
