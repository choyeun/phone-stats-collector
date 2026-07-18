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
    private static final int REQUEST_USAGE_STATS = 1002;

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

        // 권한 체크 (activity 준비 후)
        checkUsageStatsPermission();
        collectStats();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 설정에서 돌아오면 재체크
        checkUsageStatsPermission();
        requestBluetoothPermission();
    }

    /** UsageStats: 특수 권한 — 설정 페이지 바로 열기 */
    private void checkUsageStatsPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return;
        if (hasUsageStatsPermission()) return;

        Toast.makeText(this, "📊 사용량 접근 권한이 필요합니다", Toast.LENGTH_LONG).show();
        Intent intent = new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS);
        startActivityForResult(intent, REQUEST_USAGE_STATS);
    }

    /** Bluetooth: 일반 런타임 권한 */
    private void requestBluetoothPermission() {
        if (Build.VERSION.SDK_INT < 31) return;
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                == PackageManager.PERMISSION_GRANTED) return;

        if (ActivityCompat.shouldShowRequestPermissionRationale(this,
                Manifest.permission.BLUETOOTH_CONNECT)) {
            new AlertDialog.Builder(this)
                    .setTitle("🔵 블루투스 권한")
                    .setMessage("블루투스 상태를 표시하려면 권한이 필요합니다")
                    .setPositiveButton("허용", (d, w) ->
                            ActivityCompat.requestPermissions(this,
                                    new String[]{Manifest.permission.BLUETOOTH_CONNECT},
                                    REQUEST_BLUETOOTH))
                    .setNegativeButton("거부", null)
                    .show();
        } else {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.BLUETOOTH_CONNECT},
                    REQUEST_BLUETOOTH);
        }
    }

    private boolean hasUsageStatsPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT) return true;
        try {
            android.app.AppOpsManager aom = (android.app.AppOpsManager)
                    getSystemService(android.content.Context.APP_OPS_SERVICE);
            if (aom == null) return false;
            int mode = aom.checkOpNoThrow(
                    android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
                    android.os.Process.myUid(), getPackageName());
            return mode == android.app.AppOpsManager.MODE_ALLOWED;
        } catch (Exception e) {
            return false;
        }
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

            if (!hasUsageStatsPermission()) {
                sb.append("⚠️ 사용량 접근 권한 꺼짐\n");
                sb.append("   → 자동으로 설정 페이지 열었음, 활성화 후 돌아오면 반영됨\n");
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