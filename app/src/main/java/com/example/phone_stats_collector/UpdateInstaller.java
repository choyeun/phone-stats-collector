package com.example.phone_stats_collector;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;

import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

public class UpdateInstaller {
    private static final String TAG = "UpdateInstaller";
    private static final String FILE_PROVIDER_AUTHORITY = ".fileprovider";

    public interface DownloadCallback {
        void onProgress(int percent);
        void onComplete(boolean success, String message);
    }

    public static void downloadAndInstall(String downloadUrl, Context context,
                                          DownloadCallback callback) {
        File apkFile = null;
        String apkName = "bgmonitor-update.apk";
        try {
            File cacheDir = context.getCacheDir();
            if (!cacheDir.exists()) cacheDir.mkdirs();
            apkFile = new File(cacheDir, apkName);

            if (apkFile.exists()) apkFile.delete();

            HttpURLConnection conn = (HttpURLConnection) new URL(downloadUrl).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(30000);
            conn.connect();

            int responseCode = conn.getResponseCode();
            if (responseCode != 200) {
                String msg = "다운로드 실패 (HTTP " + responseCode + ")";
                Log.e(TAG, msg);
                if (callback != null) callback.onComplete(false, msg);
                return;
            }

            int totalSize = conn.getContentLength();
            Log.d(TAG, "APK 다운로드 시작: " + totalSize + " bytes");

            try (InputStream is = conn.getInputStream();
                 FileOutputStream fos = new FileOutputStream(apkFile)) {

                byte[] buffer = new byte[8192];
                int read;
                int downloaded = 0;
                int lastPercent = -1;

                while ((read = is.read(buffer)) != -1) {
                    fos.write(buffer, 0, read);
                    downloaded += read;

                    if (totalSize > 0) {
                        int percent = (int) ((long) downloaded * 100 / totalSize);
                        if (percent != lastPercent) {
                            lastPercent = percent;
                            if (callback != null) callback.onProgress(percent);
                        }
                    }
                }
            }
            conn.disconnect();

            if (callback != null) callback.onProgress(100);

            installApk(context, apkFile);
            if (callback != null) callback.onComplete(true, "다운로드 완료");

        } catch (Exception e) {
            Log.e(TAG, "다운로드/설치 실패: " + e.getMessage());
            if (apkFile != null && apkFile.exists()) apkFile.delete();
            if (callback != null) callback.onComplete(false, "설치 실패: " + e.getMessage());
        }
    }

    private static void installApk(Context context, File apkFile) {
        Uri apkUri = FileProvider.getUriForFile(
                context,
                context.getPackageName() + FILE_PROVIDER_AUTHORITY,
                apkFile
        );

        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(apkUri, "application/vnd.android.package-archive");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        context.startActivity(intent);
    }
}