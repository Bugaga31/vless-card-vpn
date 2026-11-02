package com.vlesscardvpn.core

/** Device advice cannot request any VPN mutation. Old saved command buttons use this same gate. */
object AssistantDeviceCommands {
    private val commands=setOf("!device","!cool","!boostphone")
    fun execute(command:String,sample:()->List<String>):String? {
        if(command !in commands) return null
        val tips=runCatching {sample()}.getOrElse {
            return "Датчики телефона недоступны. VPN и его настройки оставлены без изменений."
        }
        return "Локальная диагностика телефона. VPN, маски, MTU и параллелизм не изменены.\n"+
            tips.take(8).joinToString("\n") {"• "+it}.ifEmpty {"Данных для рекомендаций пока нет; ускорение не обещается."}
    }
}
