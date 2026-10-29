package com.vlesscardvpn.core

/** Ephemeral link revision, independent from persisted ISP/SSID learning identity. */
object NetworkPath {
 fun revision(iface:String,mtu:Int,addresses:List<String>,routes:List<String>,dns:List<String>):String {
  val fields=listOf(iface,mtu.toString(),addresses.distinct().sorted().joinToString("\n"),routes.distinct().sorted().joinToString("\n"),dns.distinct().sorted().joinToString("\n"))
  val encoded=fields.joinToString("") { it.length.toString()+":"+it }
  return java.security.MessageDigest.getInstance("SHA-256").digest(encoded.toByteArray(Charsets.UTF_8)).joinToString("") {"%02x".format(it.toInt() and 255)}
 }
}
