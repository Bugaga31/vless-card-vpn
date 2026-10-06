package com.vlesscardvpn.core

/** Services that can be sent through the VPN alone ("только YouTube и Telegram"), the rest goes direct. */
data class Service(val id: String, val title: String, val domains: List<String>, val ips: List<String> = emptyList())

object Services {
    val ALL = listOf(
        Service("youtube", "YouTube", listOf("geosite:youtube", "domain:googlevideo.com", "domain:ytimg.com", "domain:ggpht.com", "domain:youtu.be")),
        Service("telegram", "Telegram", listOf("geosite:telegram", "domain:t.me", "domain:telegram.org", "domain:telegram.me", "domain:telesco.pe", "domain:tdesktop.com"),
            listOf("geoip:telegram")),
        Service("instagram", "Instagram / Facebook", listOf("geosite:meta", "geosite:instagram", "geosite:facebook")),
        Service("whatsapp", "WhatsApp", listOf("geosite:whatsapp")),
        Service("discord", "Discord", listOf("geosite:discord")),
        Service("x", "X (Twitter)", listOf("geosite:twitter")),
        Service("ai", "ChatGPT и др. ИИ", listOf("geosite:openai", "domain:anthropic.com", "domain:claude.ai", "domain:gemini.google.com")),
        Service("tiktok", "TikTok", listOf("geosite:tiktok")),
    )
    private val byId = ALL.associateBy { it.id }
    fun of(ids: Collection<String>): List<Service> = ids.mapNotNull { byId[it] }
    fun domains(ids: Collection<String>): List<String> = of(ids).flatMap { it.domains }.distinct()
    fun ips(ids: Collection<String>): List<String> = of(ids).flatMap { it.ips }.distinct()
    fun label(ids: Collection<String>): String = of(ids).joinToString(" + ") { it.title.substringBefore(" ") }
}
