package com.vlesscardvpn.tor
import java.util.Locale
/** Allowed exit countries, not extra relay hops. */
object TorCountryPolicy {
 fun parse(value:String):List<String> {
  require(value.length<=200) {"Слишком длинный список стран"}
  if(value.isBlank()) return emptyList()
  val codes=value.split(',').map {it.trim().uppercase(Locale.ROOT)}.distinct()
  require(codes.all {it in Locale.getISOCountries().toSet()}) {"Нужны ISO-коды через запятую"}
  return codes
 }
 fun config(value:String):String {
  val codes=parse(value)
  return if(codes.isEmpty()) "" else "ExitNodes "+codes.joinToString(",") {"{${it.lowercase(Locale.ROOT)}}"}+"\nStrictNodes 1\n"
 }
 fun interval(seconds:Int):Long=seconds.coerceIn(5,86400)*1000L
}
