package com.example.phone_stats_collector;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

public class UpdateChecker {
    private static final String TAG = "UpdateChecker";
    private static final String GITHUB_API =
            "https://api.github.com/repos/choyeun/phone-stats-collector/releases/latest";

    public static class UpdateInfo {
        public final boolean hasUpdate;
        public final String latestVersion;
        public final String downloadUrl;
        public final long apkSize;
        public final String releaseBody;

        public UpdateInfo(boolean hasUpdate, String latestVersion,
                          String downloadUrl, long apkSize, String releaseBody) {
            this.hasUpdate = hasUpdate;
            this.latestVersion = latestVersion;
            this.downloadUrl = downloadUrl;
            this.apkSize = apkSize;
            this.releaseBody = releaseBody;
        }

        public static UpdateInfo none() {
            return new UpdateInfo(false, null, null, 0, null);
        }
    }

    public static UpdateInfo check(String currentVersion) {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(GITHUB_API).openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Accept", "application/vnd.github.v3+json");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);

            int code = conn.getResponseCode();
            if (code != 200) {
                Log.w(TAG, "GitHub API 응답 " + code);
                return UpdateInfo.none();
            }

            BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            br.close();
            conn.disconnect();

            JSONObject json = new JSONObject(sb.toString());
            String tagName = json.optString("tag_name", "");
            String body = json.optString("body", "");
            String latestVer = tagName.startsWith("v") ? tagName.substring(1) : tagName;

            String downloadUrl = null;
            long apkSize = 0;
            JSONArray assets = json.optJSONArray("assets");
            if (assets != null) {
                for (int i = 0; i < assets.length(); i++) {
                    JSONObject asset = assets.getJSONObject(i);
                    String name = asset.optString("name", "");
                    if (name.endsWith(".apk")) {
                        downloadUrl = asset.optString("browser_download_url", null);
                        apkSize = asset.optLong("size", 0);
                        break;
                    }
                }
            }
            if (downloadUrl == null) {
                downloadUrl = "https://github.com/choyeun/phone-stats-collector/releases/download/"
                        + tagName + "/phone-stats-collector-" + tagName + ".apk";
            }

            boolean hasUpdate = isNewer(currentVersion, latestVer);
            Log.d(TAG, String.format("현재=%s 최신=%s 업데이트%s",
                    currentVersion, latestVer, hasUpdate ? "있음 ✅" : "없음"));

            return new UpdateInfo(hasUpdate, latestVer, downloadUrl, apkSize, body);

        } catch (Exception e) {
            Log.e(TAG, "버전 체크 실패: " + e.getMessage());
            return UpdateInfo.none();
        }
    }

    public static boolean isNewer(String current, String latest) {
        int[] cur = parseVersion(current);
        int[] lat = parseVersion(latest);
        if (cur.length == 0 || lat.length == 0) return false;

        int len = Math.max(cur.length, lat.length);
        for (int i = 0; i < len; i++) {
            int c = i < cur.length ? cur[i] : 0;
            int l = i < lat.length ? lat[i] : 0;
            if (l > c) return true;
            if (l < c) return false;
        }
        return false;
    }

    public static int[] parseVersion(String v) {
        if (v == null || v.isEmpty()) return new int[0];
        try {
            String[] parts = v.split("\\.");
            int[] result = new int[parts.length];
            for (int i = 0; i < parts.length; i++) {
                result[i] = Integer.parseInt(parts[i]);
            }
            return result;
        } catch (NumberFormatException e) {
            return new int[0];
        }
    }
}