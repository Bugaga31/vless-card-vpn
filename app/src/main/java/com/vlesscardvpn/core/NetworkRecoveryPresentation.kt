package com.vlesscardvpn.core
object NetworkRecoveryPresentation {
 fun message(family:String):String=when {
  family.startsWith("Моб.")->"Переключились на мобильную сеть — проверяю и перестраиваю маршрут"
  family=="Wi-Fi"->"Переключились на Wi-Fi — проверяю и перестраиваю маршрут"
  else->"Сеть изменилась — проверяю и перестраиваю маршрут"
 }
}
