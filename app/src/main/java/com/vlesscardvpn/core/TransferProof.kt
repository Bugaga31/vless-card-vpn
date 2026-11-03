package com.vlesscardvpn.core

import java.io.InputStream
import java.io.IOException
import java.io.InterruptedIOException

/** Two complete body windows, never headers/HTML or a short warm-cache burst as throughput proof. */
object TransferProof {
    const val WINDOW_BYTES = 131072
    const val REQUIRED_BYTES = 2 * WINDOW_BYTES
    data class Sample(val bytes: Int, val slowestKbps: Int)
    fun rangeValid(code: Int, range: String?, minimum: Int): Boolean {
        if (code == 200) return true
        if (code != 206) return false
        val m = Regex("bytes 0-([0-9]+)/([0-9]+)").matchEntire(range.orEmpty()) ?: return false
        val end=m.groupValues[1].toLongOrNull() ?: return false
        val total=m.groupValues[2].toLongOrNull() ?: return false
        return end >= minimum.toLong()-1 && total > end
    }
    fun rate(bytes: Int, nanos: Long): Int =
        (bytes.toLong() * 8 * 1000000 / nanos.coerceAtLeast(1000000)).coerceIn(1,Int.MAX_VALUE.toLong()).toInt()
    fun read(input: InputStream, deadline: Long = Long.MAX_VALUE, now: () -> Long = System::nanoTime): Sample {
        val buffer=ByteArray(16384); var total=0; var slowest=Int.MAX_VALUE
        val prefix=java.io.ByteArrayOutputStream(64);var prefixChecked=false
        repeat(2) {
            val started=now();var window=0
            while(window<WINDOW_BYTES) {
                if (Thread.currentThread().isInterrupted || now()>=deadline) throw InterruptedIOException("Transfer deadline")
                val n=input.read(buffer,0,minOf(buffer.size,WINDOW_BYTES-window))
                if (n<0) throw IOException("Incomplete body transfer")
                if (n==0) throw IOException("Empty body read")
                if(!prefixChecked) {
                    prefix.write(buffer,0,minOf(n,64-prefix.size()))
                    if(prefix.size()==64) {
                        val start=prefix.toString("US-ASCII").trimStart().lowercase(java.util.Locale.ROOT)
                        if(listOf("<!doctype","<html","<script","{\"","[{\"").any(start::startsWith)) throw IOException("Non-binary download body")
                        prefixChecked=true
                    }
                }
                window+=n;total+=n
            }
            if(now()>=deadline) throw InterruptedIOException("Transfer deadline")
            slowest=minOf(slowest,rate(window,now()-started))
        }
        return Sample(total,slowest)
    }
    fun videoHeader(bytes: ByteArray, type: String): Boolean = when(type.substringBefore(';').trim().lowercase(java.util.Locale.ROOT)) {
        "video/mp4" -> bytes.size>=12 && String(bytes,4,4,Charsets.US_ASCII) in setOf("ftyp","styp")
        "video/webm" -> bytes.size>=4 && bytes.take(4).map { it.toInt() and 255 }==listOf(0x1a,0x45,0xdf,0xa3)
        else -> false
    }
}
