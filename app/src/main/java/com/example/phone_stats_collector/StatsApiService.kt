package com.example.phone_stats_collector

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject

/**
 * 백그라운드에서 NanoHTTPD 로컬 HTTP 서버를 실행하는 Foreground Service.
 * Termux에서 curl http://127.0.0.1:8766/api/* 로 기기 상태 조회 가능.
 */
class StatsApiService : Service() {
    companion object {
        private const val TAG = "StatsApiService"
        private const val PORT = 8766
        private const val NOTIF_ID = 8766
        private const val CHANNEL_ID = "stats_api_channel"

        @Volatile
        var isRunning = false
            private set
    }

    private var server: LocalHttpServer? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification(false))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (server == null || !server!!.isAlive) {
            try {
                server = LocalHttpServer(PORT)
                server!!.start()
                isRunning = true
                Log.i(TAG, "HTTP 서버 시작됨 (포트 $PORT)")
                // 서버 시작 알림으로 업데이트
                val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                nm.notify(NOTIF_ID, buildNotification(true))
            } catch (e: Exception) {
                Log.e(TAG, "서버 시작 실패: ${e.message}")
                isRunning = false
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        try {
            server?.stop()
        } catch (_: Exception) {}
        server = null
        Log.i(TAG, "HTTP 서버 중지됨")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /* ----- 알림 채널 + 알림 ----- */

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "BGMonitor API 서버",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "로컬 HTTP API 서버 상태"
                setShowBadge(false)
            }
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    private fun buildNotification(running: Boolean): Notification {
        val label = if (running) "실행 중 (포트 $PORT)" else "시작 중..."

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("BGMonitor API")
            .setContentText(label)
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    /* ----- NanoHTTPD 서버 ----- */

    inner class LocalHttpServer(port: Int) : NanoHTTPD(port) {
        override fun serve(session: IHTTPSession): Response {
            val uri = session.uri
            return try {
                when {
                    uri == "/api/battery" -> jsonResponse(StatsCollector.getBatteryJson(this@StatsApiService))
                    uri == "/api/stats" -> jsonResponse(buildFullStatsJson())
                    uri == "/api/apps" -> jsonResponse(buildAppsJson())
                    uri == "/api/device" -> jsonResponse(buildDeviceJson())
                    uri == "/api/health" -> jsonResponse("""{"status":"ok","service":"running","port":$PORT}""")
                    uri == "/" || uri == "/api" -> jsonResponse(buildIndex())
                    else -> newFixedLengthResponse(
                        Response.Status.NOT_FOUND, "application/json",
                        """{"error":"not_found","path":"$uri","hint":"/api"}"""
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "API 에러: ${e.message}", e)
                newFixedLengthResponse(
                    Response.Status.INTERNAL_ERROR, "application/json",
                    """{"error":"${e.message?.replace("\"", "'") ?: "unknown"}""""
                )
            }
        }

        private fun jsonResponse(json: String): Response {
            return newFixedLengthResponse(
                Response.Status.OK, "application/json; charset=utf-8", json
            )
        }

        /* ----- JSON 빌더 ----- */

        private fun buildFullStatsJson(): String {
            val ctx = this@StatsApiService
            val info = org.json.JSONObject()

            // 배터리
            val ifilter = android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED)
            val battery = ctx.registerReceiver(null, ifilter)
            if (battery != null) {
                val b = JSONObject()
                val level = battery.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1)
                val scale = battery.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1)
                b.put("percentage", if (scale > 0) level * 100.0 / scale else level)
                b.put("temperature", battery.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, 0) / 10.0)
                b.put("voltage", battery.getIntExtra(android.os.BatteryManager.EXTRA_VOLTAGE, 0) / 1000.0)
                b.put("status", battery.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1))
                b.put("health", battery.getIntExtra(android.os.BatteryManager.EXTRA_HEALTH, -1))
                info.put("battery", b)
            }

            // 기기
            val d = JSONObject()
            d.put("model", Build.MODEL)
            d.put("manufacturer", Build.MANUFACTURER)
            d.put("android_version", Build.VERSION.RELEASE)
            d.put("api_level", Build.VERSION.SDK_INT)
            d.put("uptime", StatsCollector.getUptime())
            d.put("time", StatsCollector.getCurrentTime())
            info.put("device", d)

            // 메모리
            val am = ctx.getSystemService(ACTIVITY_SERVICE) as? android.app.ActivityManager
            if (am != null) {
                val mi = android.app.ActivityManager.MemoryInfo()
                am.getMemoryInfo(mi)
                val m = JSONObject()
                m.put("total_mb", mi.totalMem / (1024 * 1024))
                m.put("available_mb", mi.availMem / (1024 * 1024))
                m.put("used_mb", (mi.totalMem - mi.availMem) / (1024 * 1024))
                info.put("memory", m)
            }

            // 화면
            val s = JSONObject()
            s.put("is_screen_on", StatsCollector.getScreenStatus(ctx).contains("켜짐"))
            s.put("bluetooth", !StatsCollector.getBluetoothStatus().contains("꺼짐"))
            info.put("status", s)

            // 앱 통계
            info.put("apps", buildAppsJson())

            // 네트워크
            val n = JSONObject()
            val wifi = StatsCollector.getWifiSignal(ctx)
            n.put("wifi", if (wifi.contains("꺼짐")) "OFF" else wifi)
            info.put("network", n)

            return info.toString(2)
        }

        private fun buildAppsJson(): String {
            val ctx = this@StatsApiService
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP_MR1) return "[]"

            val usm = ctx.getSystemService(android.content.Context.USAGE_STATS_SERVICE)
                as? android.app.usage.UsageStatsManager
                ?: return "[]"

            val end = System.currentTimeMillis()
            val start = end - 60 * 60 * 1000
            var stats = usm.queryUsageStats(
                android.app.usage.UsageStatsManager.INTERVAL_DAILY, start, end
            )
            if (stats.isNullOrEmpty()) {
                try {
                    stats = usm.queryUsageStats(
                        android.app.usage.UsageStatsManager.INTERVAL_BEST, start, end
                    )
                } catch (_: Exception) {}
            }
            if (stats == null) return "[]"

            stats.sortWith { a, b ->
                b.totalTimeInForeground.compareTo(a.totalTimeInForeground)
            }

            val arr = JSONArray()
            var count = 0
            for (u in stats) {
                if (u.totalTimeInForeground < 1000) continue
                if (count >= 20) break
                val app = JSONObject()
                app.put("package", u.packageName)
                app.put("foreground_minutes", u.totalTimeInForeground / 60000)
                app.put("last_used", u.lastTimeUsed)
                arr.put(app)
                count++
            }
            return arr.toString(2)
        }

        private fun buildDeviceJson(): String {
            val d = JSONObject()
            d.put("model", Build.MODEL)
            d.put("manufacturer", Build.MANUFACTURER)
            d.put("android_version", Build.VERSION.RELEASE)
            d.put("api_level", Build.VERSION.SDK_INT)
            d.put("uptime", StatsCollector.getUptime())
            d.put("time", StatsCollector.getCurrentTime())
            d.put("boot_time", StatsCollector.getUptime())
            return d.toString(2)
        }

        private fun buildIndex(): String {
            val idx = JSONObject()
            idx.put("name", "BGMonitor API")
            idx.put("version", BuildConfig.VERSION_NAME)
            idx.put("port", PORT)

            val endpoints = JSONArray()
            endpoints.put("GET /api/battery — 배터리 정보")
            endpoints.put("GET /api/stats — 전체 상태 JSON")
            endpoints.put("GET /api/apps — 최근 사용 앱 목록")
            endpoints.put("GET /api/device — 기기 정보")
            endpoints.put("GET /api/health — 서버 상태")
            idx.put("endpoints", endpoints)
            return idx.toString(2)
        }
    }

}