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
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket

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

    private var serverThread: Thread? = null
    private var serverSocket: ServerSocket? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (isRunning) return START_STICKY

        try {
            startForeground(NOTIF_ID, buildNotification("시작 중..."))
            serverSocket = ServerSocket(PORT)
            serverThread = Thread({ serve() }, "StatsAPI")
            serverThread!!.start()
            isRunning = true
            Log.i(TAG, "HTTP 서버 시작됨 (포트 $PORT)")
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIF_ID, buildNotification("실행 중 (포트 $PORT)"))
        } catch (e: Exception) {
            Log.e(TAG, "서버 시작 실패: ${e.message}")
            isRunning = false
            stopSelf()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        serverThread = null
        Log.i(TAG, "HTTP 서버 중지됨")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /* ----- 서버 루프 ----- */

    private fun serve() {
        try {
            while (isRunning && serverSocket != null && !serverSocket!!.isClosed) {
                try {
                    val client = serverSocket!!.accept()
                    Thread({ handleClient(client) }, "StatsAPI-Client").start()
                } catch (e: Exception) {
                    if (isRunning) Log.w(TAG, "accept 실패: ${e.message}")
                }
            }
        } catch (_: Exception) {}
    }

    private fun handleClient(client: java.net.Socket) {
        try {
            client.soTimeout = 5000
            val reader = BufferedReader(InputStreamReader(client.getInputStream()))
            val requestLine = reader.readLine() ?: return
            val parts = requestLine.split(" ")
            val rawPath = if (parts.size >= 2) parts[1] else "/"
            val path = rawPath.split("?").first()
            val query = rawPath.split("?").getOrElse(1) { "" }
            val fields = if (query.isNotEmpty()) {
                query.split("&").first { it.startsWith("fields=") }.removePrefix("fields=")
            } else null

            var line: String?
            while (reader.readLine().also { line = it } != null) {
                if (line!!.isEmpty()) break
            }

            val json = when {
                path == "/api/battery" -> StatsCollector.getBatteryJson(this@StatsApiService)
                path == "/api/stats" -> buildFullStatsJson(fields)
                path == "/api/device" -> buildDeviceJson()
                path == "/api/health" -> """{"status":"ok","service":"running","port":$PORT}"""
                else -> buildIndex()
            }

            val response = "HTTP/1.0 200 OK\r\n" +
                    "Content-Type: application/json; charset=utf-8\r\n" +
                    "Connection: close\r\n\r\n" +
                    json

            client.getOutputStream().write(response.toByteArray(Charsets.UTF_8))
            client.getOutputStream().flush()
        } catch (e: Exception) {
            Log.w(TAG, "handleClient error: ${e.message}")
        } finally {
            try { client.close() } catch (_: Exception) {}
        }
    }

    /* ----- 알림 ----- */

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

    private fun buildNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("BGMonitor API")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    /* ----- JSON 빌더 ----- */

    private fun buildFullStatsJson(fields: String? = null): String {
        val info = JSONObject()
        val want = fields?.split(",")?.map { it.trim() }?.toSet()

        fun has(name: String) = want == null || name in want!!

        val ctx = this@StatsApiService

        if (has("battery")) info.put("battery", JSONObject(StatsCollector.getBatteryJson(ctx)))
        if (has("device")) {
            val d = JSONObject()
            d.put("model", Build.MODEL)
            d.put("manufacturer", Build.MANUFACTURER)
            d.put("android_version", Build.VERSION.RELEASE)
            d.put("api_level", Build.VERSION.SDK_INT)
            d.put("uptime", StatsCollector.getUptime().trim())
            d.put("time", StatsCollector.getCurrentTime().trim())
            info.put("device", d)
        }
        if (has("cpu")) info.put("cpu", StatsCollector.getCpuUsage().trim())
        if (has("memory")) {
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
        }
        if (has("storage")) info.put("storage", StatsCollector.getStorageInfo().trim())
        if (has("network")) {
            val net = JSONObject()
            net.put("connection", StatsCollector.getNetworkInfo(ctx).trim())
            net.put("wifi", StatsCollector.getWifiSignal(ctx).trim())
            info.put("network", net)
        }
        if (has("bluetooth")) info.put("bluetooth", StatsCollector.getBluetoothStatus().trim())
        if (has("screen")) info.put("screen", StatsCollector.getScreenStatus(ctx).trim())
        if (has("running")) {
            val srv = JSONObject()
            srv.put("services", StatsCollector.getRunningServices(ctx))
            info.put("running", srv)
        }
        if (has("apps")) {
            val appsArr = JSONArray()
            for (line in StatsCollector.getRecentApps(ctx).split("\n")) {
                val t = line.trim()
                if (t.isNotEmpty() && !t.startsWith("(") && !t.startsWith("기록") && !t.startsWith("API"))
                    appsArr.put(t.removePrefix("● ").removePrefix("  "))
            }
            info.put("recent_apps", appsArr)
        }

        return info.toString(2)
    }

    private fun buildDeviceJson(): String {
        val d = JSONObject()
        d.put("model", Build.MODEL)
        d.put("manufacturer", Build.MANUFACTURER)
        d.put("android_version", Build.VERSION.RELEASE)
        d.put("api_level", Build.VERSION.SDK_INT)
        d.put("uptime", StatsCollector.getUptime().trim())
        d.put("time", StatsCollector.getCurrentTime().trim())
        return d.toString(2)
    }

    private fun buildIndex(): String {
        val idx = JSONObject()
        idx.put("name", "BGMonitor API")
        idx.put("version", BuildConfig.VERSION_NAME)
        idx.put("port", PORT)

        val endpoints = JSONArray()
        endpoints.put("GET /api/battery — 배터리")
        endpoints.put("GET /api/stats — 전체 (필터: ?fields=battery,cpu,memory,device,network,apps,...)")
        endpoints.put("GET /api/device — 기기")
        endpoints.put("GET /api/health — 상태")
        idx.put("endpoints", endpoints)
        return idx.toString(2)
    }
}