package com.vlesscardvpn.watson

import java.net.URI
import java.util.Locale
import kotlin.math.*

/** Inference only: deliberately supports an ASCII subset with Python feature parity.
 * Unsupported URL forms stay unknown. Never resolves DNS or opens a URL. */
object WatsonUrl {
    data class Result(val warning:Boolean=false,val measured:Boolean=false,val flyWarning:Boolean=false,val flyMeasured:Boolean=false)
    fun features(input:String):DoubleArray? = runCatching {
        val s=input.trim()
        if(s.isEmpty() || s.length>8192 || s.any{it.code !in 33..126} || s.contains('\\')) return null
        val u=URI(if("://" in s) s else "http://$s")
        val scheme=u.scheme?.lowercase(Locale.ROOT)
        if(scheme !in setOf("http","https") || u.rawUserInfo!=null) return null
        val host=u.host?.lowercase(Locale.ROOT)?.trimEnd('.') ?: return null
        if(host.isEmpty() || ':' in host || host.startsWith('[') || u.port>65535) return null
        if(u.rawAuthority.orEmpty().endsWith(':')) return null
        val ip=host.split('.').let{p->p.size==4 && p.all{(it.toIntOrNull() ?: -1) in 0..255 && (it=="0" || !it.startsWith('0'))}}
        val path=u.rawPath.orEmpty();val query=u.rawQuery.orEmpty()
        fun count(c:Char,limit:Int)=s.count{it==c}.coerceAtMost(limit).toDouble()
        val entropy=host.groupingBy{it}.eachCount().values.sumOf{n->val p=n.toDouble()/host.length;-p*ln(p)/ln(2.0)}
        val low=s.lowercase(Locale.ROOT)
        fun terms(vararg ts:String)=ts.sumOf{t->Regex(Regex.escape(t)).findAll(low).count()}.toDouble()
        doubleArrayOf(ln1p(s.length.toDouble()),ln1p(host.length.toDouble()),ln1p(path.length.toDouble()),ln1p(query.length.toDouble()),
            host.count{it=='.'}.coerceAtMost(20).toDouble(),host.count{it=='-'}.coerceAtMost(20).toDouble(),
            s.count{it in '0'..'9'}.toDouble()/s.length,host.count{it in '0'..'9'}.toDouble()/host.length,
            count('%',30),count('@',10),count('?',10),count('=',30),count('&',30),path.count{it=='/'}.coerceAtMost(30).toDouble(),
            if(scheme=="https") 1.0 else 0.0,if(ip) 1.0 else 0.0,if("xn--" in host) 1.0 else 0.0,0.0,
            if(u.port>=0) 1.0 else 0.0,entropy,host.split('.').maxOf{it.length}.coerceAtMost(100).toDouble(),
            terms("login","verify","account","secure","password","signin"),terms(".apk",".exe","download","update"))
    }.getOrNull()
    fun check(input:String):Result {
        val x=features(input) ?: return Result()
        val z=(x.indices.sumOf{(x[it]-WatsonUrlModel.mean[it])/WatsonUrlModel.scale[it]*WatsonUrlModel.weights[it]}+WatsonUrlModel.intercept).coerceIn(-35.0,35.0)
        val score=1.0/(1.0+exp(-z))
        val fly=runCatching {WatsonFlyUrl.score(x)}.getOrNull()
        return Result(warning=score>=WatsonUrlModel.threshold,measured=true,
            flyWarning=fly!=null && fly>=WatsonFlyUrl.threshold,flyMeasured=fly!=null)
    }
}
