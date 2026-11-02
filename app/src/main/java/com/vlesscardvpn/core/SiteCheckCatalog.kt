package com.vlesscardvpn.core

import java.net.IDN
import java.util.Locale

/** Site checks prove only a public HTTPS response over the candidate route, not login or playback. */
object SiteCheckCatalog {
    data class Site(val title:String, val domain:String)
    val presets = listOf(
        Site("Google", "www.google.com"), Site("Gmail", "mail.google.com"),
        Site("Google Drive", "drive.google.com"), Site("Google Maps", "maps.google.com"),
        Site("Google Play", "play.google.com"), Site("Google Photos", "photos.google.com"),
        Site("YouTube Music", "music.youtube.com"), Site("Wikipedia", "www.wikipedia.org"),
        Site("GitHub", "github.com"), Site("GitLab", "gitlab.com"), Site("Stack Overflow", "stackoverflow.com"),
        Site("Reddit", "www.reddit.com"), Site("Twitch", "www.twitch.tv"),
        Site("TikTok", "www.tiktok.com"), Site("X / Twitter", "x.com"), Site("Facebook", "www.facebook.com"),
        Site("Threads", "www.threads.net"), Site("WhatsApp", "www.whatsapp.com"),
        Site("Signal", "signal.org"), Site("Spotify", "www.spotify.com"), Site("SoundCloud", "soundcloud.com"),
        Site("Netflix", "www.netflix.com"), Site("Steam", "store.steampowered.com"),
        Site("Epic Games", "store.epicgames.com"), Site("Discord сайт", "discord.com"),
        Site("ChatGPT", "chatgpt.com"), Site("Claude", "claude.ai"), Site("Gemini", "gemini.google.com"),
        Site("Microsoft", "www.microsoft.com"), Site("Microsoft 365", "www.microsoft365.com"),
        Site("Apple", "www.apple.com"), Site("Cloudflare", "www.cloudflare.com"),
        Site("Notion", "www.notion.so"), Site("Figma", "www.figma.com"), Site("Dropbox", "www.dropbox.com"),
        Site("LinkedIn", "www.linkedin.com"), Site("Pinterest", "www.pinterest.com"), Site("Medium", "medium.com")
    )
    /** Input is a hostname only: never accept a URL, userinfo, port, path or IP literal. */
    fun domain(input:String):String? {
        val s=input.trim().trimEnd('.').lowercase(Locale.ROOT)
        if(s.isEmpty() || s.length>253 || s.any { it in "/:@?#\\[]" || it.isWhitespace() }) return null
        val ascii=runCatching { IDN.toASCII(s, IDN.USE_STD3_ASCII_RULES).lowercase(Locale.ROOT) }.getOrNull() ?: return null
        if(ascii.length>253) return null
        val labels=ascii.split('.')
        if(labels.size<2 || labels.any { it.isEmpty() || it.length>63 || it.first()=='-' || it.last()=='-' } || labels.last().all { it.isDigit() }) return null
        if(labels.last() in setOf("local","localhost","internal","test","invalid","example","onion")) return null
        return ascii
    }
    fun id(domain:String)="site:$domain"
    fun fromId(id:String):String? = if(id.startsWith("site:")) domain(id.removePrefix("site:"))?.takeIf { id == "site:$it" } else null
    fun response(code:Int):Boolean? = if(code in 200..299) true else null
}
