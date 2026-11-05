package com.vlesscardvpn.core

/** Ephemeral link revision, independent from persisted ISP/SSID learning identity. */
object NetworkPath {
 fun revision(iface:String,mtu:Int,addresses:List<String>,routes:List<String>,dns:List<String>):String {
  val fields=listOf(iface,mtu.toString(),addresses.distinct().sorted().joinToString("\n"),routes.distinct().sorted().joinToString("\n"),dns.distinct().sorted().joinToString("\n"))
  val encoded=fields.joinToString("") { it.length.toString()+":"+it }
  return java.security.MessageDigest.getInstance("SHA-256").digest(encoded.toByteArray(Charsets.UTF_8)).joinToString("") {"%02x".format(it.toInt() and 255)}
 }

 /**
  * 1.2.136: what an established VLESS connection actually depends on — the interface, its IPv4 addresses and
  * default gateways, and its global IPv6 /64 prefixes. Android re-announces link properties for things that do
  * not break a single socket: IPv6 address lifetimes refreshed by every router advertisement, a rotated
  * temporary IPv6 address inside the same prefix, a DNS server added or dropped, an MTU update. Each of those
  * used to look like a brand-new network, cancelled every job, reset all servers' runtime evidence and rebuilt
  * the route («постоянно переподключается»). One short hash per element (no raw address is kept),
  * sorted and comma-joined, so that a pure addition can be recognised by [extends].
  * [addresses] are «address/prefix» strings (extra words after a space are ignored); [gateways] are the
  * gateways of default routes (only IPv4 ones count: IPv6 routers announce themselves by link-local addresses
  * that legitimately change without breaking anything).
  */
 fun transport(iface:String,addresses:List<String>,gateways:List<String>):String {
  val elements=sortedSetOf<String>()
  elements+="if:"+iface.trim()
  for (raw in addresses) element(raw)?.let { elements+=it }
  for (raw in gateways) {
   val g=raw.trim().substringBefore(' ').substringBefore('%')
   if (g.isNotEmpty() && ipv4(g)!=null) elements+="gw4:"+g
  }
  return elements.joinToString(",") { short(it) }
 }

 /** The new path keeps every element of the old one and only adds (an IPv6 prefix arriving after IPv4, a second
  * address). Established connections on the old elements are untouched by such a change. */
 fun extends(old:String,new:String):Boolean {
  if (old.isBlank() || new.isBlank()) return false
  val before=old.split(',').filter { it.isNotEmpty() }.toSet()
  val after=new.split(',').filter { it.isNotEmpty() }.toSet()
  return before.isNotEmpty() && after.containsAll(before)
 }

 private fun element(raw:String):String? {
  val token=raw.trim().substringBefore(' ')
  if (token.isEmpty()) return null
  val address=token.substringBefore('/').substringBefore('%')
  val prefix=token.substringAfter('/',"").toIntOrNull()
  ipv4(address)?.let { return "v4:"+it.joinToString(".") { b -> (b.toInt() and 255).toString() }+"/"+(prefix ?: 32) }
  val v6=ipv6(address) ?: return null
  val first=v6[0].toInt() and 255
  // Link-local (fe80::/10) and multicast never carry the VLESS connection; skip them.
  if (first==0xfe && (v6[1].toInt() and 0xc0)==0x80 || first==0xff) return null
  return "v6:"+(0 until 8).joinToString("") { "%02x".format(v6[it].toInt() and 255) }+"/64"
 }

 private fun ipv4(text:String):ByteArray? {
  val parts=text.split('.')
  if (parts.size!=4) return null
  val bytes=ByteArray(4)
  for ((i,p) in parts.withIndex()) {
   if (p.isEmpty() || p.length>3 || p.any { !it.isDigit() }) return null
   val v=p.toInt(); if (v>255) return null
   bytes[i]=v.toByte()
  }
  return bytes
 }

 /** Literal-only IPv6 parsing: never a DNS lookup (Android resolves an invalid literal as a host name). */
 private fun ipv6(text:String):ByteArray? {
  if (text.isEmpty() || text.any { it !in "0123456789abcdefABCDEF:." } || !text.contains(':')) return null
  var s=text
  var tail:ByteArray?=null
  val lastColon=s.lastIndexOf(':')
  val last=s.substring(lastColon+1)
  if (last.contains('.')) { tail=ipv4(last) ?: return null; s=s.substring(0,lastColon+1)+"0:0" }
  val gap=s.indexOf("::")
  if (gap>=0 && s.indexOf("::",gap+1)>=0) return null
  fun groups(part:String):List<Int>? {
   if (part.isEmpty()) return emptyList()
   val out=ArrayList<Int>()
   for (g in part.split(':')) { if (g.isEmpty() || g.length>4) return null; out+=g.toIntOrNull(16) ?: return null }
   return out
  }
  val head=groups(if (gap>=0) s.substring(0,gap) else s) ?: return null
  val rest=groups(if (gap>=0) s.substring(gap+2) else "") ?: return null
  val all=if (gap>=0) { if (head.size+rest.size>7) return null; head+List(8-head.size-rest.size) { 0 }+rest }
   else { if (head.size!=8) return null; head }
  val bytes=ByteArray(16)
  all.forEachIndexed { i,g -> bytes[2*i]=(g shr 8).toByte(); bytes[2*i+1]=g.toByte() }
  tail?.copyInto(bytes,12)
  return bytes
 }

 private fun short(element:String):String =
  java.security.MessageDigest.getInstance("SHA-256").digest(element.toByteArray(Charsets.UTF_8)).take(6)
   .joinToString("") { "%02x".format(it.toInt() and 255) }
}
