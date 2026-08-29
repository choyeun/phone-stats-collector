package com.example.phone_stats_collector

import android.app.ActivityManager
import android.app.usage.UsageStats
import android.app.usage.UsageStatsManager
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.util.Log
import java.io.BufferedReader
import java.io.FileReader
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 기기 상태 정보를 수집하는 유틸리티 */
@Suppress("DEPRECATION")
object StatsCollector {
    private const val TAG = "StatsCollector"

    /** 실행 중인 서비스 목록을 문자열로 반환 */
    fun getRunningServices(ctx: Context): String {
        val sb = StringBuilder()
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return "  (권한 부족 또는 오류)"
        val services = am.getRunningServices(100)
        if (services.isNullOrEmpty()) return "  (실행 중인 포그라운드 서비스 없음)"

        var count = 0
        for (s in services) {
            if (s.foreground) {
                sb.append("  🔵 ${s.service.className}\n")
                count++
            }
        }
        for (s in services) {
            if (!s.foreground && s.service.packageName == ctx.packageName) {
                sb.append("  ⚪ ${s.service.className}\n")
                count++
            }
        }
        if (count == 0) sb.append("  (없음)\n")
        return sb.toString().trim()
    }

    /** 최근 사용 앱 목록 (UsageStatsManager) */
    fun getRecentApps(ctx: Context): String {
        val sb = StringBuilder()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
            val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
                ?: return "  (UsageStatsManager 없음)"

            val end = System.currentTimeMillis()
            val start = end - 60 * 60 * 1000 // 최근 1시간
            var stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, start, end)

            if (stats.isNullOrEmpty()) {
                try {
                    stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_BEST, start, end)
                } catch (_: Exception) {}
            }

            if (stats != null) {
                // 사용 시간 기준 내림차순 정렬
                stats.sortWith { a, b ->
                    b.totalTimeInForeground.compareTo(a.totalTimeInForeground)
                }

                var shown = 0
                for (u in stats) {
                    if (u.totalTimeInForeground < 1000) continue
                    if (shown >= 10) break
                    val mins = u.totalTimeInForeground / 60000
                    sb.append("  ● ${u.packageName} (${mins}분)\n")
                    shown++
                }
                if (shown == 0) sb.append("  (기록 없음 — 사용량 접근 권한 필요)\n")
            } else {
                sb.append("  (기록 없음 — 사용량 접근 권한 필요)\n")
            }
        } else {
            sb.append("  (API 지원 안 함)\n")
        }
        return sb.toString().trim()
    }

    /** 배터리 정보 */
    fun getBatteryInfo(ctx: Context): String {
        val sb = StringBuilder()
        val ifilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val battery = ctx.registerReceiver(null, ifilter)

        if (battery == null) return "  (배터리 정보 없음)"

        val level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val temp = battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)
        val voltage = battery.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)
        val status = battery.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val health = battery.getIntExtra(BatteryManager.EXTRA_HEALTH, -1)
        val tech = battery.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY)

        val pct = if (scale > 0) level * 100f / scale else level.toFloat()
        val tempC = temp / 10f
        val voltageV = voltage / 1000f

        sb.append(String.format(Locale.US, "  잔량: %.0f%%\n", pct))
        sb.append(String.format(Locale.US, "  온도: %.1f°C\n", tempC))
        sb.append(String.format(Locale.US, "  전압: %.3fV\n", voltageV))

        val statusStr = when (status) {
            BatteryManager.BATTERY_STATUS_CHARGING -> "충전 중 ⚡"
            BatteryManager.BATTERY_STATUS_DISCHARGING -> "방전 중"
            BatteryManager.BATTERY_STATUS_FULL -> "완충 ✅"
            BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "충전 안 함"
            else -> "알 수 없음"
        }
        sb.append("  상태: $statusStr\n")

        val healthStr = when (health) {
            BatteryManager.BATTERY_HEALTH_GOOD -> "양호 ✅"
            BatteryManager.BATTERY_HEALTH_OVERHEAT -> "과열 🔥"
            BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "과전압"
            BatteryManager.BATTERY_HEALTH_DEAD -> "수명 다함 💀"
            BatteryManager.BATTERY_HEALTH_COLD -> "저온 ❄️"
            else -> "보통"
        }
        sb.append("  건강도: $healthStr\n")
        if (tech != null) sb.append("  기술: $tech")
        return sb.toString()
    }

    /** 배터리 정보 JSON */
    fun getBatteryJson(ctx: Context): String {
        val ifilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val battery = ctx.registerReceiver(null, ifilter) ?: return """{"error":"battery info unavailable"}"""

        val level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val temp = battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)
        val status = battery.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val health = battery.getIntExtra(BatteryManager.EXTRA_HEALTH, -1)
        val plugged = battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)

        val pct = if (scale > 0) level * 100f / scale else level.toFloat()
        val statusStr = when (status) {
            BatteryManager.BATTERY_STATUS_CHARGING -> "CHARGING"
            BatteryManager.BATTERY_STATUS_DISCHARGING -> "DISCHARGING"
            BatteryManager.BATTERY_STATUS_FULL -> "FULL"
            BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "NOT_CHARGING"
            else -> "UNKNOWN"
        }
        val healthStr = when (health) {
            BatteryManager.BATTERY_HEALTH_GOOD -> "GOOD"
            BatteryManager.BATTERY_HEALTH_OVERHEAT -> "OVERHEAT"
            BatteryManager.BATTERY_HEALTH_DEAD -> "DEAD"
            else -> "OK"
        }

        return """{
  "percentage": $pct,
  "status": "$statusStr",
  "health": "$healthStr",
  "temperature": ${temp / 10f},
  "voltage": ${voltage / 1000f},
  "plugged": $plugged
}"""
    }

    /** CPU 사용률 — /proc/stat → dumpsys cpuinfo fallback (Android 14+ 대응) */
    fun getCpuUsage(): String {
        val sb = StringBuilder()
        try {
            // 시도 1: /proc/stat
            val line = readFirstLine("/proc/stat")
            if (line != null && line.startsWith("cpu")) {
                val parts = line.trim().split("\\s+".toRegex())
                if (parts.size >= 5) {
                    val user = parts[1].toLong()
                    val nice = parts[2].toLong()
                    val sys = parts[3].toLong()
                    val idle = parts[4].toLong()
                    val total = user + nice + sys + idle
                    val used = user + nice + sys
                    val usage = used * 100f / total
                    sb.append(String.format(Locale.US, "  시스템 전체: %.1f%%\n", usage))
                    return sb.toString()
                }
            }
            // 시도 2: dumpsys cpuinfo
            val dumpsysOutput = execCommand("dumpsys cpuinfo")
            if (!dumpsysOutput.isNullOrEmpty()) {
                for (l in dumpsysOutput.split("\n")) {
                    if (l.contains("TOTAL")) {
                        sb.append("  ${l.trim()}\n")
                        return sb.toString()
                    }
                }
                for (l in dumpsysOutput.split("\n")) {
                    if (l.contains("Load:")) {
                        sb.append("  ${l.trim()}\n")
                        break
                    }
                }
                var count = 0
                for (l in dumpsysOutput.split("\n")) {
                    val tl = l.trim()
                    if (tl.matches("^\\d+%.*".toRegex()) && !tl.contains("TOTAL") && count < 3) {
                        sb.append("  $tl\n")
                        count++
                    }
                }
                return sb.toString()
            }
            // 시도 3: /proc/self/stat
            val selfLine = readFirstLine("/proc/self/stat")
            if (selfLine != null) {
                val parts = selfLine.trim().split("\\s+".toRegex())
                if (parts.size >= 14) {
                    val utime = parts[13].toLong()
                    val stime = parts[14].toLong()
                    sb.append(String.format(Locale.US, "  이 앱 CPU: %d ticks\n", utime + stime))
                    return sb.toString()
                }
            }
            sb.append("  (CPU 정보 불가)")
        } catch (e: Exception) {
            sb.append("  (CPU 정보 읽기 실패)")
        }
        return sb.toString()
    }

    /** 파일 첫 줄 읽기 */
    private fun readFirstLine(path: String): String? {
        try {
            BufferedReader(FileReader(path)).use { return it.readLine() }
        } catch (_: Exception) {
            return null
        }
    }

    /** 셸 명령어 실행 */
    private fun execCommand(cmd: String): String? {
        try {
            val process = Runtime.getRuntime().exec(cmd)
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val sb = StringBuilder()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                sb.append(line).append("\n")
            }
            reader.close()
            process.waitFor()
            return sb.toString().trim()
        } catch (_: Exception) {
            return null
        }
    }

    /** 메모리 정보 */
    fun getMemoryInfo(ctx: Context): String {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return "  (메모리 정보 없음)"

        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)

        val totalMB = mi.totalMem / (1024 * 1024)
        val availMB = mi.availMem / (1024 * 1024)
        val usedMB = totalMB - availMB
        val pct = usedMB * 100f / totalMB

        return """  총: ${totalMB} MB
  사용: ${usedMB} MB (${"%.0f".format(Locale.US, pct)}%)
  가용: ${availMB} MB"""
    }

    /** 저장공간 정보 */
    fun getStorageInfo(): String {
        return try {
            val dataDir = Environment.getDataDirectory()
            val stat = StatFs(dataDir.path)
            val totalBytes = stat.totalBytes
            val freeBytes = stat.freeBytes
            val usedBytes = totalBytes - freeBytes
            val totalGB = totalBytes / (1024 * 1024 * 1024)
            val usedGB = usedBytes / (1024 * 1024 * 1024)
            val pct = usedGB * 100f / totalGB
            """  총: ${totalGB} GB
  사용: ${usedGB} GB (${"%.0f".format(Locale.US, pct)}%)"""
        } catch (_: Exception) {
            "  (저장공간 정보 없음)"
        }
    }

    /** 네트워크 상태 */
    fun getNetworkInfo(ctx: Context): String {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return "  (네트워크 정보 없음)"

        val active = cm.activeNetworkInfo
        if (active == null || !active.isConnected) return "  연결 안 됨 ❌"

        val type = when (active.type) {
            ConnectivityManager.TYPE_WIFI -> "Wi-Fi"
            ConnectivityManager.TYPE_MOBILE -> when (active.subtype) {
                13 -> "LTE (4G)"
                20 -> "5G NR"
                3 -> "3G"
                else -> "모바일 (${active.subtype})"
            }
            ConnectivityManager.TYPE_ETHERNET -> "이더넷"
            else -> "기타 (${active.typeName})"
        }

        val sb = StringBuilder()
        sb.append("  타입: $type\n")
        if (active.isRoaming) sb.append("  로밍: 예 🌍\n")
        sb.append("  연결됨 ✅")
        return sb.toString()
    }

    /** WiFi 신호 세기 */
    fun getWifiSignal(ctx: Context): String {
        return try {
            val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                ?: return "  (WiFi 꺼짐)"
            if (!wm.isWifiEnabled) return "  (WiFi 꺼짐)"

            val rssi = wm.connectionInfo.rssi
            val level = WifiManager.calculateSignalLevel(rssi, 5)
            val bars = when (level) {
                0 -> "⬜⬜⬜⬜"
                1 -> "🟩⬜⬜⬜"
                2 -> "🟩🟩⬜⬜"
                3 -> "🟩🟩🟩⬜"
                else -> "🟩🟩🟩🟩"
            }
            "  신호: $bars ($rssi dBm)"
        } catch (_: Exception) {
            "  (WiFi 정보 없음)"
        }
    }

    /** 블루투스 상태 */
    fun getBluetoothStatus(): String {
        return try {
            val ba = BluetoothAdapter.getDefaultAdapter()
            if (ba == null) "  (블루투스 미지원)"
            else if (ba.isEnabled) "  켜짐 🟦" else "  꺼짐"
        } catch (_: SecurityException) {
            "  (권한 없음)"
        }
    }

    /** 화면 상태 */
    fun getScreenStatus(ctx: Context): String {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        var screenOn = false
        if (am != null) {
            val procs = am.runningAppProcesses
            if (!procs.isNullOrEmpty()) {
                screenOn = procs[0].importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
            }
        }
        return if (screenOn) "  켜짐 🔆" else "  꺼짐 🌙"
    }

    /** 현재 시각 */
    fun getCurrentTime(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.KOREAN)
        return "  ${sdf.format(Date())}"
    }

    /** 부팅 이후 경과 시간 */
    fun getUptime(): String {
        val uptimeMs = System.currentTimeMillis() - getBootTime()
        val hours = uptimeMs / (3600 * 1000)
        val mins = (uptimeMs % (3600 * 1000)) / (60 * 1000)
        return String.format(Locale.US, "  %d시간 %d분", hours, mins)
    }

    private fun getBootTime(): Long {
        try {
            BufferedReader(FileReader("/proc/stat")).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    if (line!!.startsWith("btime")) {
                        val bootSec = line!!.split("\\s+".toRegex())[1].toLong()
                        return bootSec * 1000
                    }
                }
            }
        } catch (_: Exception) {}
        return System.currentTimeMillis()
    }
}