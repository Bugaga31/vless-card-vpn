package com.vlesscardvpn.domain
object AppAppearance {
    val modes = listOf("system", "light", "dark")
    fun normalize(value: String) = if (value in modes) value else "system"
    fun isDark(mode: String, systemDark: Boolean) = when (normalize(mode)) { "dark" -> true; "light" -> false; else -> systemDark }
}
