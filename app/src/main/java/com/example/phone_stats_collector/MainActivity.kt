package com.example.phone_stats_collector

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.method.ScrollingMovementMethod
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.util.Locale
import java.util.concurrent.Executors

@Suppress("DEPRECATION")
class MainActivity : AppCompatActivity() {
    companion object {
        private const val TAG = "MainActivity"
        private const val REQUEST_BLUETOOTH = 1001
        private const val REQUEST_USAGE_STATS = 1002
    }

    private lateinit var tvInfo: TextView
    private lateinit var btnRefresh: Button
    private lateinit var btnUpdateCheck: Button
    private lateinit var scrollView: ScrollView
    private lateinit var btnService: Button

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvInfo = findViewById(R.id.tv_info)
        btnRefresh = findViewById(R.id.btn_refresh)
        btnUpdateCheck = findViewById(R.id.btn_update)
        scrollView = findViewById(R.id.scroll_view)
        btnService = findViewById(R.id.btn_service)

        tvInfo.movementMethod = ScrollingMovementMethod()
        tvInfo.setTextIsSelectable(true)

        btnRefresh.setOnClickListener { collectStats() }
        btnUpdateCheck.setOnClickListener { checkForUpdate() }
        btnService.setOnClickListener { toggleService() }

        checkUsageStatsPermission()
        collectStats()
        updateServiceButton()
    }

    override fun onResume() {
        super.onResume()
        checkUsageStatsPermission()
        requestBluetoothPermission()
        updateServiceButton()
    }

    private fun toggleService() {
        val intent = Intent(this, StatsApiService::class.java)
        if (StatsApiService.isRunning) {
            stopService(intent)
            Toast.makeText(this, "🛑 API 서버 중지됨", Toast.LENGTH_SHORT).show()
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED
                ) {
                    ActivityCompat.requestPermissions(
                        this,
                        arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                        1003
                    )
                    Toast.makeText(this, "알림 권한을 허용해야 서비스가 실행됩니다", Toast.LENGTH_LONG).show()
                    return
                }
            }
            ContextCompat.startForegroundService(this, intent)
            Toast.makeText(this, "🚀 API 서버 시작 (포트 8766)", Toast.LENGTH_SHORT).show()
        }
        updateServiceButton()
    }

    private fun updateServiceButton() {
        btnService.text = if (StatsApiService.isRunning)
            "⏹ 서비스 중지 (8766)" else "▶ 서비스 시작"
    }

    /* ----- 권한 ----- */

    private fun checkUsageStatsPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return
        if (hasUsageStatsPermission()) return

        Toast.makeText(this, "📊 사용량 접근 권한이 필요합니다", Toast.LENGTH_LONG).show()
        val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
        startActivityForResult(intent, REQUEST_USAGE_STATS)
    }

    private fun requestBluetoothPermission() {
        if (Build.VERSION.SDK_INT < 31) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
            == PackageManager.PERMISSION_GRANTED
        ) return

        if (ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.BLUETOOTH_CONNECT)) {
            AlertDialog.Builder(this)
                .setTitle("🔵 블루투스 권한")
                .setMessage("블루투스 상태를 표시하려면 권한이 필요합니다")
                .setPositiveButton("허용") { _, _ ->
                    ActivityCompat.requestPermissions(
                        this, arrayOf(Manifest.permission.BLUETOOTH_CONNECT), REQUEST_BLUETOOTH
                    )
                }
                .setNegativeButton("거부", null)
                .show()
        } else {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.BLUETOOTH_CONNECT), REQUEST_BLUETOOTH
            )
        }
    }

    private fun hasUsageStatsPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT) return true
        return try {
            val aom = getSystemService(APP_OPS_SERVICE) as android.app.AppOpsManager
            aom.checkOpNoThrow(
                android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(), packageName
            ) == android.app.AppOpsManager.MODE_ALLOWED
        } catch (_: Exception) {
            false
        }
    }

    /* ----- 수집 ----- */

    private fun collectStats() {
        btnRefresh.isEnabled = false
        btnRefresh.text = "수집 중..."

        executor.execute {
            val sb = StringBuilder()

            sb.append("━━━ 📱 기기 정보 ━━━\n")
            sb.append("  기종: ${Build.MODEL}\n")
            sb.append("  제조사: ${Build.MANUFACTURER}\n")
            sb.append("  Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n")
            sb.append("  수집시각: ${StatsCollector.getCurrentTime()}\n")
            sb.append("  실행시간: ${StatsCollector.getUptime()}\n\n")

            sb.append("━━━ 🔋 배터리 ━━━\n")
            sb.append("${StatsCollector.getBatteryInfo(this)}\n\n")

            sb.append("━━━ 💻 CPU / 메모리 ━━━\n")
            sb.append("CPU:\n${StatsCollector.getCpuUsage()}\n")
            sb.append("RAM:\n${StatsCollector.getMemoryInfo(this)}\n")
            sb.append("저장공간:\n${StatsCollector.getStorageInfo()}\n\n")

            sb.append("━━━ 🌐 네트워크 ━━━\n")
            sb.append("${StatsCollector.getNetworkInfo(this)}\n")
            sb.append("${StatsCollector.getWifiSignal(this)}\n")
            sb.append("블루투스:\n${StatsCollector.getBluetoothStatus()}\n")
            sb.append("화면:\n${StatsCollector.getScreenStatus(this)}\n\n")

            sb.append("━━━ 🔄 실행 중인 서비스 ━━━\n")
            sb.append("${StatsCollector.getRunningServices(this)}\n\n")

            sb.append("━━━ 📊 최근 사용 앱 (1h) ━━━\n")
            sb.append("${StatsCollector.getRecentApps(this)}\n\n")

            sb.append("━━━ 🌐 API 서버 ━━━\n")
            sb.append(if (StatsApiService.isRunning)
                "  실행 중 (포트 8766) ✅\n"
            else
                "  꺼짐 ❌\n")
            sb.append("  Termux: curl http://127.0.0.1:8766/api/stats\n\n")

            if (!hasUsageStatsPermission()) {
                sb.append("⚠️ 사용량 접근 권한 꺼짐\n")
                sb.append("   → 자동으로 설정 페이지 열었음, 활성화 후 돌아오면 반영됨\n")
            }

            val info = sb.toString()

            mainHandler.post {
                tvInfo.text = info
                scrollView.scrollTo(0, 0)
                btnRefresh.isEnabled = true
                btnRefresh.text = "🔄 새로고침"
            }
        }
    }

    /* ----- 업데이트 ----- */

    private fun checkForUpdate() {
        btnUpdateCheck.isEnabled = false
        btnUpdateCheck.text = "확인 중..."

        executor.execute {
            val currentVer = BuildConfig.VERSION_NAME
            val info = UpdateChecker.check(currentVer)

            mainHandler.post {
                btnUpdateCheck.isEnabled = true
                btnUpdateCheck.text = "🔍 업데이트 확인"

                if (!info.hasUpdate) {
                    if (info.latestVersion == null) {
                        Toast.makeText(this@MainActivity, "업데이트 확인 실패 (네트워크?)", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this@MainActivity, "최신 버전입니다 ($currentVer)", Toast.LENGTH_SHORT).show()
                    }
                    return@post
                }

                val sizeKB = info.apkSize / 1024
                val sizeMB = sizeKB / 1024
                val sizeStr = if (sizeMB > 0) "${sizeMB} MB" else "${sizeKB} KB"

                AlertDialog.Builder(this@MainActivity)
                    .setTitle("업데이트 발견 🚀")
                    .setMessage(String.format(Locale.US,
                        "현재: v%s → 최신: v%s\n크기: %s\n\n%s",
                        currentVer, info.latestVersion, sizeStr, info.releaseBody ?: ""))
                    .setPositiveButton("다운로드") { _, _ -> downloadUpdate(info.downloadUrl) }
                    .setNegativeButton("취소", null)
                    .show()
            }
        }
    }

    private fun downloadUpdate(url: String) {
        Toast.makeText(this, "다운로드 시작...", Toast.LENGTH_SHORT).show()
        btnUpdateCheck.isEnabled = false
        btnUpdateCheck.text = "⬇️ 다운로드 중..."

        executor.execute {
            UpdateInstaller.downloadAndInstall(url, this@MainActivity,
                object : UpdateInstaller.DownloadCallback {
                    override fun onProgress(percent: Int) {
                        mainHandler.post {
                            btnUpdateCheck.text = "⬇️ $percent%"
                        }
                    }

                    override fun onComplete(success: Boolean, message: String?) {
                        mainHandler.post {
                            btnUpdateCheck.isEnabled = true
                            btnUpdateCheck.text = "🔍 업데이트 확인"
                            if (success) {
                                Toast.makeText(this@MainActivity, "설치를 시작합니다", Toast.LENGTH_LONG).show()
                            } else {
                                Toast.makeText(this@MainActivity, "실패: $message", Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                })
        }
    }
}