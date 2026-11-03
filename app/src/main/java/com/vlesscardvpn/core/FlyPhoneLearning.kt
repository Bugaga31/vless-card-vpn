package com.vlesscardvpn.core
import kotlin.math.*

/** Online future heat/memory readout. No synthetic antivirus/energy labels; no VPN controls. */
class FlyPhoneLearning {
    var samples=0; private set
    var positives=0; private set
    var negatives=0; private set
    private var weights=DoubleArray(22)
    private fun features(x:DoubleArray)=FlyReservoir.encode(DoubleArray(5){((x[it]+1.0)/2.0).coerceIn(0.0,1.0)})
    fun predict(x:DoubleArray):Double {
        if(x.size!=5 || x.any{!it.isFinite()}) return .5
        val f=features(x);val z=f.indices.sumOf{f[it]*weights[it]}.coerceIn(-20.0,20.0)
        return 1.0/(1.0+exp(-z))
    }
    fun train(x:DoubleArray,hotLater:Boolean) {
        if(x.size!=5 || x.any{!it.isFinite()}) return
        val f=features(x);val error=(if(hotLater) 1.0 else 0.0)-predict(x)
        for(i in weights.indices) weights[i]=(weights[i]+.03*error*f[i]).coerceIn(-4.0,4.0)
        samples=(samples+1).coerceAtMost(1000000)
        if(hotLater) positives=(positives+1).coerceAtMost(1000000) else negatives=(negatives+1).coerceAtMost(1000000)
    }
    fun ready()=samples>=30 && positives>=3 && negatives>=3
    fun encode()=(listOf(samples.toString(),positives.toString(),negatives.toString())+weights.map{it.toString()}).joinToString(",")
    fun restore(text:String) {
        val p=text.split(',');require(p.size==25)
        val count=p.take(3).map{it.toInt()};require(count.all{it in 0..1000000} && count[1]<=count[0] && count[2]<=count[0])
        val w=p.drop(3).map{it.toDouble()};require(w.all{it.isFinite() && it in -4.0..4.0})
        samples=count[0];positives=count[1];negatives=count[2];weights=w.toDoubleArray()
    }
}
