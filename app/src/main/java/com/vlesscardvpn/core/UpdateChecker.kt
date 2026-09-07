package com.vlesscardvpn.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URL

/**
 * Checks GitHub releases for newer versions of the app.
 */
data class UpdateInfo(
    val latestVersion: String,
    val currentVersion: String,
    val updateAvailable: Boolean,
    val downloadUrl: String,
    val releaseNotes: String,
    val publishedAt: String
)

object UpdateChecker {
    private const val GITHUB_API = "https://api.github.com/repos/Bugaga31/vless-card-vpn/releases/latest"

    suspend fun checkForUpdates(currentVersion: String): UpdateInfo? = withContext(Dispatchers.IO) {
        try {
            val connection = URL(GITHUB_API).openConnection() as javax.net.ssl.HttpsURLConnection
            connection.connectTimeout = 10000
            connection.readTimeout = 10000
            connection.setRequestProperty("Accept", "application/vnd.github.v3+json")
            connection.setRequestProperty("User-Agent", "VLESS-Card-Update/1.0")

            if (connection.responseCode != 200) return@withContext null

            val body = connection.inputStream.bufferedReader().readText()
            val json = JSONObject(body)

            val tagName = json.optString("tag_name", "").removePrefix("v")
            val htmlUrl = json.optString("html_url", "")
            val notes = json.optString("body", "")
            val publishedAt = json.optString("published_at", "")

            // Find APK asset
            val assets = json.optJSONArray("assets") ?: return@withContext null
            var downloadUrl = ""
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                if (asset.optString("name", "").endsWith(".apk")) {
                    downloadUrl = asset.optString("browser_download_url", "")
                    break
                }
            }

            val latestVer = parseVersion(tagName)
            val currentVer = parseVersion(currentVersion)

            UpdateInfo(
                latestVersion = tagName,
                currentVersion = currentVersion,
                updateAvailable = latestVer > currentVer,
                downloadUrl = downloadUrl,
                releaseNotes = notes,
                publishedAt = publishedAt
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun parseVersion(version: String): Int {
        return try {
            version.split(".").map { it.toIntOrNull() ?: 0 }
                .fold(0) { acc, part -> acc * 1000 + part }
        } catch (_: Exception) {
            0
        }
    }
}