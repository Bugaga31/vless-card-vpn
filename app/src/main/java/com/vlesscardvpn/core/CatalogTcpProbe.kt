package com.vlesscardvpn.core

import com.vlesscardvpn.model.Server
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.SocketChannel
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean

/** Whole-catalog reachability, not a protocol verdict. Numeric endpoints need not wait for DNS.
 * One fixed bounded resolver pool; a hung native DNS call cannot grow threads or hold the scan open.
 * -1 means inconclusive, never an automatic server failure. No TLS/SNI substitutions here.
 */
object CatalogTcpProbe {
    private val numericHost=Regex("[0-9.]+")
    private val dns = ThreadPoolExecutor(4,4,0,TimeUnit.MILLISECONDS,ArrayBlockingQueue<Runnable>(32),
        { task -> Thread(task,"catalog-dns").apply { isDaemon=true } },ThreadPoolExecutor.AbortPolicy())
    data class Summary(val started: Int, val completed: Int, val reachable: Int, val total: Int)
    fun scan(servers: List<Server>, budgetMs: Long = 6000, timeoutMs: Int = 1200, window: Int = 192,
             resolve: (String) -> InetAddress? = { runCatching { InetAddress.getByName(it) }.getOrNull() },
             bindCancel: ((() -> Unit) -> Unit) = {}, onStart: (Int) -> Unit = {},
             onEach: (Int, Int) -> Unit): Summary {
        if (servers.isEmpty()) return Summary(0,0,0,0)
        val deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(budgetMs.coerceIn(1,15000))
        val selector=Selector.open();val stopped=AtomicBoolean()
        val open=ConcurrentHashMap<Int,SocketChannel>();val starts=HashMap<Int,Long>()
        val reported=HashSet<Int>();var started=0;var completed=0;var reachable=0
        val ready=java.util.ArrayDeque<Pair<Int,InetAddress>>()
        // Reuse only the cheap reachability hint, never protocol/service proof, across duplicate endpoints.
        val aliases=servers.indices.groupBy { Triple(servers[it].address,servers[it].port,servers[it].isTcpBased) }
            .values.associate { it.first() to it }
        val groups=aliases.keys.groupBy { servers[it].address.removeSurrounding("[","]") }
        val waiting=java.util.ArrayDeque(groups.keys);val resolving=linkedMapOf<String,Future<InetAddress?>>()
        fun finish(i: Int, ms: Int) {
            open.remove(i)?.let { runCatching { it.close() } };starts.remove(i)
            aliases.getValue(i).forEach { index ->
                if (reported.add(index)) { if(ms>=0)completed++;if(ms>0)reachable++;onEach(index,ms) }
            }
        }
        fun begin(i: Int) { aliases.getValue(i).forEach { started++;onStart(it) } }
        fun resolved(host: String, address: InetAddress?) {
            groups.getValue(host).forEach { i ->
                if(address==null) finish(i,-1) else if(!servers[i].isTcpBased) { begin(i);finish(i,1) }
                else ready.add(i to address)
            }
        }
        bindCancel { stopped.set(true);selector.wakeup();open.values.forEach { runCatching { it.close() } } }
        try {
            // Do not queue numeric hosts behind slow/unresolvable domain names.
            val names=waiting.toList();waiting.clear()
            names.forEach { host ->
                if(host.contains(':') || numericHost.matches(host)) resolved(host,runCatching { InetAddress.getByName(host) }.getOrNull())
                else waiting.add(host)
            }
            while(!stopped.get() && !Thread.currentThread().isInterrupted && System.nanoTime()<deadline &&
                (ready.isNotEmpty() || open.isNotEmpty() || waiting.isNotEmpty() || resolving.isNotEmpty())) {
                while(waiting.isNotEmpty() && resolving.size<16) {
                    val host=waiting.removeFirst()
                    try { resolving[host]=dns.submit(Callable { resolve(host) }) }
                    catch (_: RejectedExecutionException) { resolved(host,null) }
                }
                val done=resolving.filterValues { it.isDone }.keys.toList()
                done.forEach { host -> resolved(host,runCatching { resolving.remove(host)!!.get() }.getOrNull()) }
                while(ready.isNotEmpty() && open.size<window.coerceIn(1,192) && System.nanoTime()<deadline && !stopped.get()) {
                    val (i,address)=ready.removeFirst();begin(i)
                    val channel=runCatching { SocketChannel.open().apply { configureBlocking(false) } }.getOrNull()
                    if(channel==null) { finish(i,-1);continue }
                    open[i]=channel;starts[i]=System.nanoTime()
                    try {
                        if(channel.connect(InetSocketAddress(address,servers[i].port))) finish(i,1)
                        else channel.register(selector,SelectionKey.OP_CONNECT,i)
                    } catch (_: Exception) { finish(i,0) }
                }
                selector.select(15)
                val keys=selector.selectedKeys().iterator()
                while(keys.hasNext()) {
                    val key=keys.next();keys.remove();val i=key.attachment() as Int;key.cancel()
                    val success=runCatching { (key.channel() as SocketChannel).finishConnect() }.getOrDefault(false)
                    finish(i,if(success) ((System.nanoTime()-(starts[i] ?: System.nanoTime()))/1000000).toInt().coerceAtLeast(1) else 0)
                }
                val expired=System.nanoTime()-TimeUnit.MILLISECONDS.toNanos(timeoutMs.coerceAtLeast(1).toLong())
                starts.filterValues { it<expired }.keys.toList().forEach { finish(it,-1) }
            }
        } finally {
            stopped.set(true);open.values.forEach { runCatching { it.close() } };runCatching { selector.close() }
            resolving.values.forEach { it.cancel(true) };dns.purge()
        }
        return Summary(started,completed,reachable,servers.size)
    }
}
