package com.vlesscardvpn.core

import java.net.InetAddress

/** Strict numeric literals only. Never performs a hostname lookup or invents NAT64 prefixes.
 * Scoped IPv6 is left to the platform resolver; zone IDs must not be silently discarded.
 */
object NumericProbeAddress {
    private fun ipv4(text:String):ByteArray? {
        val parts=text.split('.')
        if(parts.size!=4) return null
        val bytes=ByteArray(4)
        for((i,p) in parts.withIndex()) {
            if(p.isEmpty() || p.length>3 || p.any {it !in '0'..'9'} || (p.length>1 && p[0]=='0')) return null
            val n=p.toIntOrNull() ?: return null
            if(n !in 0..255) return null
            bytes[i]=n.toByte()
        }
        return bytes
    }
    fun parse(value:String):InetAddress? {
        if(value.startsWith("[") && ':' !in value) return null
        val text=if(value.startsWith("[") && value.endsWith("]")) value.substring(1,value.length-1) else value
        if(text.isEmpty() || text.length>45 || '%' in text || text.any {it.isWhitespace()}) return null
        if(':' !in text) return ipv4(text)?.let {InetAddress.getByAddress(it)}
        if(text.any {it !in "0123456789abcdefABCDEF:."}) return null
        val compressed=text.indexOf("::")
        if(compressed>=0 && text.indexOf("::",compressed+2)>=0) return null
        fun words(part:String):List<Int>? {
            if(part.isEmpty()) return emptyList()
            val out=mutableListOf<Int>();val chunks=part.split(':')
            for((i,p) in chunks.withIndex()) {
                if('.' in p) {
                    if(i!=chunks.lastIndex) return null
                    val b=ipv4(p) ?: return null
                    out+=((b[0].toInt() and 255) shl 8) or (b[1].toInt() and 255)
                    out+=((b[2].toInt() and 255) shl 8) or (b[3].toInt() and 255)
                } else {
                    if(p.length !in 1..4) return null
                    out+=p.toIntOrNull(16) ?: return null
                }
            }
            return out
        }
        val left=words(if(compressed<0) text else text.substring(0,compressed)) ?: return null
        val right=if(compressed<0) emptyList() else words(text.substring(compressed+2)) ?: return null
        // Embedded IPv4 may appear only at the end of the entire address.
        if(compressed>=0 && '.' in text.substring(0,compressed)) return null
        val count=left.size+right.size
        if((compressed<0 && count!=8) || (compressed>=0 && count>=8)) return null
        val all=left+List(if(compressed<0) 0 else 8-count){0}+right
        val bytes=ByteArray(16)
        all.forEachIndexed {i,n->bytes[i*2]=(n shr 8).toByte();bytes[i*2+1]=n.toByte()}
        return InetAddress.getByAddress(bytes)
    }
}
