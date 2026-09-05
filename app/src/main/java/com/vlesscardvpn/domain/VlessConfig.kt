package com.vlesscardvpn.domain

data class VlessConfig(
    val id: String = java.util.UUID.randomUUID().toString(),
    val name: String,
    val address: String,
    val port: Int,
    val uuid: String,
    val flow: String = "xtls-rprx-vision",
    val security: String = "reality",
    val sni: String = "samsung.com",
    val fingerprint: String = "chrome",
    val publicKey: String = "",
    val shortId: String = "",
    val remark: String = "",
    val isActive: Boolean = false,
    val pingMs: Int = -1,
    val isFree: Boolean = false
)

object SampleConfigs {
    val freeExamples = listOf(
        "vless://11111111-1111-1111-1111-111111111111@free1.example.com:443?type=tcp&security=reality&pbk=publickey1&fp=chrome&sni=yandex.ru&sid=123456&spx=%2F&flow=xtls-rprx-vision#Free-Reality-1",
        "vless://22222222-2222-2222-2222-222222222222@free2.example.net:443?type=tcp&security=reality&pbk=publickey2&fp=chrome&sni=samsung.com&sid=abcdef&spx=%2F&flow=xtls-rprx-vision#Free-Reality-2",
        "vless://33333333-3333-3333-3333-333333333333@free3.public.org:443?type=tcp&security=reality&pbk=publickey3&fp=chrome&sni=apple.com&sid=7890ab&spx=%2F&flow=xtls-rprx-vision#Free-Reality-3"
    )
}