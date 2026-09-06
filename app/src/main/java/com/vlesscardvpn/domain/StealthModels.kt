package com.vlesscardvpn.domain

import java.util.UUID

enum class StealthProfileType(
    val id: String,
    val title: String,
    val description: String,
    val defaultSni: String,
    val defaultAlpn: List<String>,
    val tlsFingerprint: String,
    val minPacketSize: Int,
    val maxPacketSize: Int,
    val delayRangeMs: IntRange,
    val stealthScore: Int,
    val fakeEndpoints: List<String>
) {
    YANDEX(
        id = "yandex",
        title = "Яндекс",
        description = "Имитация Chromium 122 на Windows. Максимальный обход ТСПУ в РФ.",
        defaultSni = "yandex.ru",
        defaultAlpn = listOf("h2", "http/1.1"),
        tlsFingerprint = "chrome_122",
        minPacketSize = 512,
        maxPacketSize = 1460,
        delayRangeMs = 100..300,
        stealthScore = 98,
        fakeEndpoints = listOf("/favicon.ico", "/images/logo.png", "/search/suggest")
    ),
    VK(
        id = "vk",
        title = "ВКонтакте",
        description = "Имитация Firefox 115 Mobile. Трафик звонков и картинок VK.",
        defaultSni = "vk.com",
        defaultAlpn = listOf("http/1.1"),
        tlsFingerprint = "firefox_115",
        minPacketSize = 256,
        maxPacketSize = 768,
        delayRangeMs = 50..200,
        stealthScore = 95,
        fakeEndpoints = listOf("/images/logos/logo.png", "/blank.html", "/al_audio.php")
    ),
    APPLE(
        id = "apple",
        title = "Apple iCloud",
        description = "Имитация Safari 17 iOS. Прикрытие под Private Relay.",
        defaultSni = "gateway.icloud.com",
        defaultAlpn = listOf("h2"),
        tlsFingerprint = "safari_17",
        minPacketSize = 400,
        maxPacketSize = 1350,
        delayRangeMs = 80..220,
        stealthScore = 91,
        fakeEndpoints = listOf("/hotspot-detect.html", "/success.txt")
    ),
    SAMSUNG(
        id = "samsung",
        title = "Samsung Cloud",
        description = "Имитация Samsung Browser. Нативный Android-фон.",
        defaultSni = "samsung.com",
        defaultAlpn = listOf("h2", "http/1.1"),
        tlsFingerprint = "chrome",
        minPacketSize = 380,
        maxPacketSize = 1400,
        delayRangeMs = 120..280,
        stealthScore = 88,
        fakeEndpoints = listOf("/generate_204", "/push_token")
    ),
    CUSTOM(
        id = "custom",
        title = "Свой профиль",
        description = "Ручные параметры SNI, ALPN и отпечатка TLS.",
        defaultSni = "speedtest.net",
        defaultAlpn = listOf("h2", "http/1.1"),
        tlsFingerprint = "randomized",
        minPacketSize = 512,
        maxPacketSize = 1400,
        delayRangeMs = 50..200,
        stealthScore = 80,
        fakeEndpoints = listOf("/favicon.ico")
    )
}

data class StealthSettings(
    val activeProfile: StealthProfileType = StealthProfileType.YANDEX,
    val customSni: String = "",
    val enableNoiseGenerator: Boolean = true,
    val noiseIntervalSeconds: Int = 45,
    val enableInstantFingerprintRotation: Boolean = true,
    val rotationIntervalMinutes: Int = 8,
    val fractalFragmentationLevel: Int = 5, // 1 to 10
    val tunnelIntegrityPercent: Int = 99,
    val lastRouteShuffleTime: Long = System.currentTimeMillis()
)

data class DiagnosticResult(
    val tgStatus: TestState = TestState.IDLE,
    val tgPingMs: Int = -1,
    val tgVerdict: String = "Готов к запуску",

    val ytStatus: TestState = TestState.IDLE,
    val ytSpeedMbps: Double = 0.0,
    val ytLatencyMs: Int = -1,
    val ytVerdict: String = "Готов к запуску",
    val ytPacketLoss: Double = 0.0,

    val rknStatus: TestState = TestState.IDLE,
    val rknPassRatePercent: Int = 0,
    val rknPassedCount: Int = 0,
    val rknTotalTested: Int = 0,
    val rknVerdict: String = "Готов к запуску"
) {
    enum class TestState {
        IDLE, RUNNING, SUCCESS, WARNING, FAILED
    }
}
