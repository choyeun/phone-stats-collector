package com.example.phone_stats_collector

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

object UpdateChecker {
    private const val TAG = "UpdateChecker"
    private const val GITHUB_API =
        "https://api.github.com/repos/choyeun/phone-stats-collector/releases/latest"

    data class UpdateInfo(
        val hasUpdate: Boolean,
        val latestVersion: String?,
        val downloadUrl: String?,
        val apkSize: Long,
        val releaseBody: String?
    ) {
        companion object {
            fun none() = UpdateInfo(false, null, null, 0, null)
        }
    }

    fun check(currentVersion: String): UpdateInfo {
        try {
            val conn = URL(GITHUB_API).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("Accept", "application/vnd.github.v3+json")
            conn.connectTimeout = 5000
            conn.readTimeout = 5000

            val code = conn.responseCode
            if (code != 200) {
                Log.w(TAG, "GitHub API 응답 $code")
                return UpdateInfo.none()
            }

            val reader = BufferedReader(InputStreamReader(conn.inputStream))
            val sb = StringBuilder()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                sb.append(line)
            }
            reader.close()
            conn.disconnect()

            val json = JSONObject(sb.toString())
            val tagName = json.optString("tag_name", "")
            val body = json.optString("body", "")
            val latestVer = if (tagName.startsWith("v")) tagName.substring(1) else tagName

            var downloadUrl: String? = null
            var apkSize: Long = 0
            val assets = json.optJSONArray("assets")
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val asset = assets.getJSONObject(i)
                    val name = asset.optString("name", "")
                    if (name.endsWith(".apk")) {
                        downloadUrl = asset.optString("browser_download_url", null)
                        apkSize = asset.optLong("size", 0)
                        break
                    }
                }
            }
            if (downloadUrl == null) {
                downloadUrl = "https://github.com/choyeun/phone-stats-collector/releases/download/$tagName/phone-stats-collector-$tagName.apk"
            }

            val hasUpdate = isNewer(currentVersion, latestVer)
            Log.d(TAG, "현재=$currentVersion 최신=$latestVer 업데이트${if (hasUpdate) "있음 ✅" else "없음"}")

            return UpdateInfo(hasUpdate, latestVer, downloadUrl, apkSize, body)

        } catch (e: Exception) {
            Log.e(TAG, "버전 체크 실패: ${e.message}")
            return UpdateInfo.none()
        }
    }

    fun isNewer(current: String, latest: String): Boolean {
        val cur = parseVersion(current)
        val lat = parseVersion(latest)
        if (cur.isEmpty() || lat.isEmpty()) return false

        val len = maxOf(cur.size, lat.size)
        for (i in 0 until len) {
            val c = if (i < cur.size) cur[i] else 0L
            val l = if (i < lat.size) lat[i] else 0L
            if (l > c) return true
            if (l < c) return false
        }
        return false
    }

    fun parseVersion(v: String?): LongArray {
        if (v.isNullOrEmpty()) return LongArray(0)
        return try {
            val parts = v.split("\\.".toRegex())
            LongArray(parts.size) { i ->
                parts[i].replace("[^0-9]".toRegex(), "").toLong()
            }
        } catch (_: NumberFormatException) {
            LongArray(0)
        }
    }
}