package com.example.phone_stats_collector;

import android.app.ActivityManager;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Environment;
import android.os.StatFs;
import android.os.UserHandle;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** 기기 상태 정보를 수집하는 유틸리티 */
public class StatsCollector {
    private static final String TAG = "StatsCollector";

    /** 실행 중인 서비스 목록을 문자열로 반환 */
    public static String getRunningServices(Context ctx) {
        StringBuilder sb = new StringBuilder();
        ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
        if (am == null) return "  (권한 부족 또는 오류)";
        List<ActivityManager.RunningServiceInfo> services = am.getRunningServices(100);
        if (services == null || services.isEmpty()) {
            return "  (실행 중인 포그라운드 서비스 없음)";
        }
        int count = 0;
        for (ActivityManager.RunningServiceInfo s : services) {
            if (s.foreground) {
                sb.append("  🔵 ").append(s.service.getClassName()).append("\n");
                count++;
            }
        }
        // 자기 앱 서비스도 포함
        for (ActivityManager.RunningServiceInfo s : services) {
            if (!s.foreground &&
                s.service.getPackageName().equals(ctx.getPackageName())) {
                sb.append("  ⚪ ").append(s.service.getClassName()).append("\n");
                count++;
            }
        }
        if (count == 0) sb.append("  (없음)\n");
        return sb.toString().trim();
    }

    /** 최근 사용 앱 목록 (UsageStatsManager) */
    public static String getRecentApps(Context ctx) {
        StringBuilder sb = new StringBuilder();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
            UsageStatsManager usm = (UsageStatsManager)
                    ctx.getSystemService(Context.USAGE_STATS_SERVICE);
            if (usm == null) return "  (UsageStatsManager 없음)";

            long end = System.currentTimeMillis();
            long start = end - 60 * 60 * 1000; // 최근 1시간
            List<UsageStats> stats = usm.queryUsageStats(
                    UsageStatsManager.INTERVAL_DAILY, start, end);

            if (stats == null || stats.isEmpty()) {
                // INTERVAL_DAILY가 빌 수 있음 → INTERVAL_BEST 시도
                try {
                    stats = usm.queryUsageStats(
                            UsageStatsManager.INTERVAL_BEST, start, end);
                } catch (Exception ignored) {}
            }

            if (stats != null) {
                // 사용 시간 기준 내림차순 정렬
                stats.sort((a, b) ->
                        Long.compare(b.getTotalTimeInForeground(), a.getTotalTimeInForeground()));

                // 최대 10개
                int shown = 0;
                for (UsageStats u : stats) {
                    if (u.getTotalTimeInForeground() < 1000) continue;
                    if (shown >= 10) break;
                    long mins = u.getTotalTimeInForeground() / 60000;
                    sb.append("  ● ")
                      .append(u.getPackageName())
                      .append(" (").append(mins).append("분)\n");
                    shown++;
                }
                if (shown == 0) sb.append("  (기록 없음 — 사용량 접근 권한 필요)\n");
            } else {
                sb.append("  (기록 없음 — 사용량 접근 권한 필요)\n");
            }
        } else {
            sb.append("  (API 지원 안 함)\n");
        }
        return sb.toString().trim();
    }

    /** 배터리 정보 */
    public static String getBatteryInfo(Context ctx) {
        StringBuilder sb = new StringBuilder();
        IntentFilter ifilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        Intent battery = ctx.registerReceiver(null, ifilter);

        if (battery == null) {
            return "  (배터리 정보 없음)";
        }

        int level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
        int temp = battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1);
        int voltage = battery.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1);
        int status = battery.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        int health = battery.getIntExtra(BatteryManager.EXTRA_HEALTH, -1);
        String tech = battery.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY);

        float pct = scale > 0 ? (level * 100f / scale) : level;
        float tempC = temp / 10f;
        float voltageV = voltage / 1000f;

        sb.append(String.format(Locale.US, "  잔량: %.0f%%\n", pct));
        sb.append(String.format(Locale.US, "  온도: %.1f°C\n", tempC));
        sb.append(String.format(Locale.US, "  전압: %.3fV\n", voltageV));

        String statusStr;
        switch (status) {
            case BatteryManager.BATTERY_STATUS_CHARGING: statusStr = "충전 중 ⚡"; break;
            case BatteryManager.BATTERY_STATUS_DISCHARGING: statusStr = "방전 중"; break;
            case BatteryManager.BATTERY_STATUS_FULL: statusStr = "완충 ✅"; break;
            case BatteryManager.BATTERY_STATUS_NOT_CHARGING: statusStr = "충전 안 함"; break;
            default: statusStr = "알 수 없음"; break;
        }
        sb.append("  상태: ").append(statusStr).append("\n");

        String healthStr;
        switch (health) {
            case BatteryManager.BATTERY_HEALTH_GOOD: healthStr = "양호 ✅"; break;
            case BatteryManager.BATTERY_HEALTH_OVERHEAT: healthStr = "과열 🔥"; break;
            case BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE: healthStr = "과전압"; break;
            case BatteryManager.BATTERY_HEALTH_DEAD: healthStr = "수명 다함 💀"; break;
            case BatteryManager.BATTERY_HEALTH_COLD: healthStr = "저온 ❄️"; break;
            default: healthStr = "보통"; break;
        }
        sb.append("  건강도: ").append(healthStr).append("\n");
        if (tech != null) sb.append("  기술: ").append(tech);

        return sb.toString();
    }

    /** CPU 사용률 — /proc/stat → /proc/self/stat fallback (Android 14+ 대응) */
    public static String getCpuUsage() {
        StringBuilder sb = new StringBuilder();
        try {
            // 시도 1: /proc/stat (Android 13 이하)
            String line = readFirstLine("/proc/stat");
            if (line != null && line.startsWith("cpu")) {
                String[] parts = line.trim().split("\\s+");
                if (parts.length >= 5) {
                    long user = Long.parseLong(parts[1]);
                    long nice = Long.parseLong(parts[2]);
                    long sys = Long.parseLong(parts[3]);
                    long idle = Long.parseLong(parts[4]);
                    long total = user + nice + sys + idle;
                    long used = user + nice + sys;
                    float usage = (used * 100f) / total;
                    sb.append(String.format(Locale.US, "  시스템 전체: %.1f%%\n", usage));
                    return sb.toString();
                }
            }
            // 시도 2: dumpsys cpuinfo (Android 14+ 호환)
            String dumpsysOutput = execCommand("dumpsys cpuinfo");
            if (dumpsysOutput != null && !dumpsysOutput.isEmpty()) {
                for (String l : dumpsysOutput.split("\n")) {
                    if (l.contains("TOTAL")) {
                        sb.append("  ").append(l.trim()).append("\n");
                        return sb.toString();
                    }
                }
                // TOTAL 라인이 없으면 Load 라인이라도 표시
                for (String l : dumpsysOutput.split("\n")) {
                    if (l.contains("Load:")) {
                        sb.append("  ").append(l.trim()).append("\n");
                        break;
                    }
                }
                // 상위 3개 프로세스 CPU 표시
                int count = 0;
                for (String l : dumpsysOutput.split("\n")) {
                    l = l.trim();
                    if (l.matches("^\\d+%.*") && !l.contains("TOTAL") && count < 3) {
                        sb.append("  ").append(l).append("\n");
                        count++;
                    }
                }
                return sb.toString();
            }
            // 시도 3: /proc/self/stat (최후)
            line = readFirstLine("/proc/self/stat");
            if (line != null) {
                String[] parts = line.trim().split("\\s+");
                if (parts.length >= 14) {
                    long utime = Long.parseLong(parts[13]);
                    long stime = Long.parseLong(parts[14]);
                    long totalTicks = utime + stime;
                    sb.append(String.format(Locale.US, "  이 앱 CPU: %d ticks\n", totalTicks));
                    return sb.toString();
                }
            }
            sb.append("  (CPU 정보 불가)");
        } catch (Exception e) {
            sb.append("  (CPU 정보 읽기 실패)");
        }
        return sb.toString();
    }

    /** 파일 첫 줄 읽기 (실패 시 null) */
    private static String readFirstLine(String path) {
        try {
            BufferedReader br = new BufferedReader(new FileReader(path));
            String line = br.readLine();
            br.close();
            return line;
        } catch (Exception e) {
            return null;
        }
    }

    /** Shell 명령어 실행 (실패 시 null) */
    private static String execCommand(String cmd) {
        try {
            Process process = Runtime.getRuntime().exec(cmd);
            BufferedReader br = new BufferedReader(
                    new InputStreamReader(process.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append("\n");
            }
            br.close();
            process.waitFor();
            return sb.toString().trim();
        } catch (Exception e) {
            return null;
        }
    }

    /** 메모리 정보 */
    public static String getMemoryInfo(Context ctx) {
        StringBuilder sb = new StringBuilder();
        ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
        if (am == null) return "  (메모리 정보 없음)";

        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);

        long totalMB = mi.totalMem / (1024 * 1024);
        long availMB = mi.availMem / (1024 * 1024);
        long usedMB = totalMB - availMB;
        float pct = (usedMB * 100f) / totalMB;

        sb.append(String.format(Locale.US, "  총: %d MB\n", totalMB));
        sb.append(String.format(Locale.US, "  사용: %d MB (%.0f%%)\n", usedMB, pct));
        sb.append(String.format(Locale.US, "  가용: %d MB", availMB));
        return sb.toString();
    }

    /** 저장공간 정보 */
    public static String getStorageInfo() {
        StringBuilder sb = new StringBuilder();
        try {
            File dataDir = Environment.getDataDirectory();
            StatFs stat = new StatFs(dataDir.getPath());
            long totalBytes = stat.getTotalBytes();
            long freeBytes = stat.getFreeBytes();
            long usedBytes = totalBytes - freeBytes;

            long totalGB = totalBytes / (1024 * 1024 * 1024);
            long usedGB = usedBytes / (1024 * 1024 * 1024);
            float pct = (usedGB * 100f) / totalGB;

            sb.append(String.format(Locale.US, "  총: %d GB\n", totalGB));
            sb.append(String.format(Locale.US, "  사용: %d GB (%.0f%%)", usedGB, pct));
        } catch (Exception e) {
            sb.append("  (저장공간 정보 없음)");
        }
        return sb.toString();
    }

    /** 네트워크 상태 */
    public static String getNetworkInfo(Context ctx) {
        StringBuilder sb = new StringBuilder();
        ConnectivityManager cm = (ConnectivityManager)
                ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return "  (네트워크 정보 없음)";

        NetworkInfo active = cm.getActiveNetworkInfo();
        if (active == null || !active.isConnected()) {
            return "  연결 안 됨 ❌";
        }

        String type;
        switch (active.getType()) {
            case ConnectivityManager.TYPE_WIFI: type = "Wi-Fi"; break;
            case ConnectivityManager.TYPE_MOBILE:
                int subtype = active.getSubtype();
                switch (subtype) {
                    case 13: type = "LTE (4G)"; break;
                    case 20: type = "5G NR"; break;
                    case 3: type = "3G"; break;
                    default: type = "모바일 (" + subtype + ")"; break;
                }
                break;
            case ConnectivityManager.TYPE_ETHERNET: type = "이더넷"; break;
            default: type = "기타 (" + active.getTypeName() + ")"; break;
        }

        sb.append("  타입: ").append(type).append("\n");
        if (active.isRoaming()) sb.append("  로밍: 예 🌍\n");
        sb.append("  연결됨 ✅");
        return sb.toString();
    }

    /** WiFi 신호 세기 */
    public static String getWifiSignal(Context ctx) {
        try {
            WifiManager wm = (WifiManager)
                    ctx.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm == null || !wm.isWifiEnabled()) return "  (WiFi 꺼짐)";

            int rssi = wm.getConnectionInfo().getRssi();
            int level = WifiManager.calculateSignalLevel(rssi, 5);
            String bars;
            switch (level) {
                case 0: bars = "⬜⬜⬜⬜"; break;
                case 1: bars = "🟩⬜⬜⬜"; break;
                case 2: bars = "🟩🟩⬜⬜"; break;
                case 3: bars = "🟩🟩🟩⬜"; break;
                default: bars = "🟩🟩🟩🟩"; break;
            }
            return "  신호: " + bars + " (" + rssi + " dBm)";
        } catch (Exception e) {
            return "  (WiFi 정보 없음)";
        }
    }

    /** 블루투스 상태 */
    public static String getBluetoothStatus() {
        try {
            BluetoothAdapter ba = BluetoothAdapter.getDefaultAdapter();
            if (ba == null) return "  (블루투스 미지원)";
            return ba.isEnabled() ? "  켜짐 🟦" : "  꺼짐";
        } catch (SecurityException e) {
            return "  (권한 없음)";
        }
    }

    /** 화면 상태 (켜짐/꺼짐) */
    public static String getScreenStatus(Context ctx) {
        ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
        boolean screenOn = false;
        if (am != null) {
            // RunningAppProcessInfo 첫번째 프로세스의 중요도로 간접 추정
            List<ActivityManager.RunningAppProcessInfo> procs = am.getRunningAppProcesses();
            if (procs != null && !procs.isEmpty()) {
                // IMPORTANCE_FOREGROUND = 화면 켜짐 상태 가능성
                screenOn = procs.get(0).importance ==
                        ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND;
            }
        }
        return screenOn ? "  켜짐 🔆" : "  꺼짐 🌙";
    }

    /** 현재 시각 */
    public static String getCurrentTime() {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.KOREAN);
        return "  " + sdf.format(new Date());
    }

    /** 부팅 이후 경과 시간 (uptime) */
    public static String getUptime() {
        long uptimeMs = System.currentTimeMillis() - getBootTime();
        long hours = uptimeMs / (3600 * 1000);
        long mins = (uptimeMs % (3600 * 1000)) / (60 * 1000);
        return String.format(Locale.US, "  %d시간 %d분", hours, mins);
    }

    private static long getBootTime() {
        try {
            BufferedReader br = new BufferedReader(new FileReader("/proc/stat"));
            String line;
            while ((line = br.readLine()) != null) {
                if (line.startsWith("btime")) {
                    long bootSec = Long.parseLong(line.split("\\s+")[1]);
                    br.close();
                    return bootSec * 1000;
                }
            }
            br.close();
        } catch (Exception ignored) {}
        return System.currentTimeMillis();
    }
}