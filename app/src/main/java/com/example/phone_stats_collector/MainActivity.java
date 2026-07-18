package com.example.phone_stats_collector;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.method.ScrollingMovementMethod;
import android.util.Log;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";
    private static final int REQUEST_BLUETOOTH = 1001;

    private TextView tvInfo;
    private Button btnRefresh;
    private Button btnUpdateCheck;
    private ScrollView scrollView;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvInfo = findViewById(R.id.tv_info);
        btnRefresh = findViewById(R.id.btn_refresh);
        btnUpdateCheck = findViewById(R.id.btn_update);
        scrollView = findViewById(R.id.scroll_view);

        tvInfo.setMovementMethod(new ScrollingMovementMethod());
        tvInfo.setTextIsSelectable(true);

        btnRefresh.setOnClickListener(v -> collectStats());
        btnUpdateCheck.setOnClickListener(v -> checkForUpdate());

        // 첫 실행 시 권한 체크
        checkPermissions();
        collectStats();
    }

    /** 필요 권한들을 한번에 체크 + 요청 */
    private void checkPermissions() {
        // 1. UsageStats 권한 (special permission — 설정으로 직접 이동)
        if (!hasUsageStatsPermission()) {
            showUsageStatsDialog();
            return;
        }

        // 2. Bluetooth 권한 (Android 12+)
        if (Build.VERSION.SDK_INT >= 31) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.BLUETOOTH_CONNECT},
                        REQUEST_BLUETOOTH);
            }
        }
    }

    /** UsageStats 권한 보유 여부 */
    private boolean hasUsageStatsPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return true;
        try {
            android.app.usage.UsageStatsManager usm =
                    (android.app.usage.UsageStatsManager) getSystemService(USAGE_STATS_SERVICE);
            if (usm == null) return false;
            long end = System.currentTimeMillis();
            long start = end - 1000;
            return usm.queryUsageStats(
                    android.app.usage.UsageStatsManager.INTERVAL_DAILY,
                    start, end) != null;
        } catch (Exception e) {
            return false;
        }
    }

    /** UsageStats 권한 요청 다이얼로그 */
    private void showUsageStatsDialog() {
        new AlertDialog.Builder(this)
                .setTitle("📊 사용량 접근 권한 필요")
                .setMessage("최근 사용 앱 목록을 수집하려면 '사용량 접근' 권한이 필요합니다.\n\n" +
                        "설정 → 사용량 접근 → BGMonitor 활성화 해주세요.")
                .setPositiveButton("설정으로 이동", (d, w) -> {
                    Intent intent = new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS);
                    startActivity(intent);
                    Toast.makeText(this, "권한 활성화 후 새로고침 해주세요", Toast.LENGTH_LONG).show();
                })
                .setNegativeButton("다음에", null)
                .setCancelable(false)
                .show();
    }

    private void collectStats() {
        btnRefresh.setEnabled(false);
        btnRefresh.setText("수집 중...");

        executor.execute(() -> {
            StringBuilder sb = new StringBuilder();

            sb.append("━━━ 📱 기기 정보 ━━━\n");
            sb.append("  기종: ").append(Build.MODEL).append("\n");
            sb.append("  제조사: ").append(Build.MANUFACTURER).append("\n");
            sb.append("  Android: ").append(Build.VERSION.RELEASE)
              .append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
            sb.append("  수집시각: ").append(StatsCollector.getCurrentTime()).append("\n");
            sb.append("  실행시간: ").append(StatsCollector.getUptime()).append("\n\n");

            sb.append("━━━ 🔋 배터리 ━━━\n");
            sb.append(StatsCollector.getBatteryInfo(this)).append("\n\n");

            sb.append("━━━ 💻 CPU / 메모리 ━━━\n");
            sb.append("CPU:\n");
            sb.append(StatsCollector.getCpuUsage()).append("\n");
            sb.append("RAM:\n");
            sb.append(StatsCollector.getMemoryInfo(this)).append("\n");
            sb.append("저장공간:\n");
            sb.append(StatsCollector.getStorageInfo()).append("\n\n");

            sb.append("━━━ 🌐 네트워크 ━━━\n");
            sb.append(StatsCollector.getNetworkInfo(this)).append("\n");
            sb.append(StatsCollector.getWifiSignal(this)).append("\n");
            sb.append("블루투스:\n");
            sb.append(StatsCollector.getBluetoothStatus()).append("\n");
            sb.append("화면:\n");
            sb.append(StatsCollector.getScreenStatus(this)).append("\n\n");

            sb.append("━━━ 🔄 실행 중인 서비스 ━━━\n");
            sb.append(StatsCollector.getRunningServices(this)).append("\n\n");

            sb.append("━━━ 📊 최근 사용 앱 (1h) ━━━\n");
            sb.append(StatsCollector.getRecentApps(this)).append("\n\n");

            // UsageStats 권한 상태 안내
            if (!hasUsageStatsPermission()) {
                sb.append("⚠️ 사용량 접근 권한 꺼짐\n");
                sb.append("   → 앱 상단 다이얼로그에서 설정으로 이동 가능\n");
            }

            String info = sb.toString();

            mainHandler.post(() -> {
                tvInfo.setText(info);
                scrollView.scrollTo(0, 0);
                btnRefresh.setEnabled(true);
                btnRefresh.setText("🔄 새로고침");
            });
        });
    }

    private void checkForUpdate() {
        btnUpdateCheck.setEnabled(false);
        btnUpdateCheck.setText("확인 중...");

        executor.execute(() -> {
            String currentVer = BuildConfig.VERSION_NAME;
            UpdateChecker.UpdateInfo info = UpdateChecker.check(currentVer);

            mainHandler.post(() -> {
                btnUpdateCheck.setEnabled(true);
                btnUpdateCheck.setText("🔍 업데이트 확인");

                if (!info.hasUpdate) {
                    if (info.latestVersion == null) {
                        Toast.makeText(this, "업데이트 확인 실패 (네트워크?)", Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(this, "최신 버전입니다 (" + currentVer + ")", Toast.LENGTH_SHORT).show();
                    }
                    return;
                }

                long sizeKB = info.apkSize / 1024;
                long sizeMB = sizeKB / 1024;
                String sizeStr = sizeMB > 0
                        ? String.format(Locale.US, "%d MB", sizeMB)
                        : String.format(Locale.US, "%d KB", sizeKB);

                new AlertDialog.Builder(this)
                        .setTitle("업데이트 발견 🚀")
                        .setMessage(String.format(Locale.US,
                                "현재: v%s → 최신: v%s\n크기: %s\n\n%s",
                                currentVer, info.latestVersion, sizeStr,
                                info.releaseBody != null ? info.releaseBody : ""))
                        .setPositiveButton("다운로드", (d, w) -> downloadUpdate(info.downloadUrl))
                        .setNegativeButton("취소", null)
                        .show();
            });
        });
    }

    private void downloadUpdate(String url) {
        Toast.makeText(this, "다운로드 시작...", Toast.LENGTH_SHORT).show();
        btnUpdateCheck.setEnabled(false);
        btnUpdateCheck.setText("⬇️ 다운로드 중...");

        executor.execute(() -> {
            UpdateInstaller.downloadAndInstall(url, this, new UpdateInstaller.DownloadCallback() {
                @Override
                public void onProgress(int percent) {
                    mainHandler.post(() ->
                            btnUpdateCheck.setText("⬇️ " + percent + "%"));
                }

                @Override
                public void onComplete(boolean success, String message) {
                    mainHandler.post(() -> {
                        btnUpdateCheck.setEnabled(true);
                        btnUpdateCheck.setText("🔍 업데이트 확인");
                        if (success) {
                            Toast.makeText(MainActivity.this,
                                    "설치를 시작합니다", Toast.LENGTH_LONG).show();
                        } else {
                            Toast.makeText(MainActivity.this,
                                    "실패: " + message, Toast.LENGTH_LONG).show();
                        }
                    });
                }
            });
        });
    }
}