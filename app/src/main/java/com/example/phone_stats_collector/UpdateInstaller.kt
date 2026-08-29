package com.example.phone_stats_collector

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

object UpdateInstaller {
    private const val TAG = "UpdateInstaller"
    private const val FILE_PROVIDER_AUTHORITY = ".fileprovider"

    interface DownloadCallback {
        fun onProgress(percent: Int)
        fun onComplete(success: Boolean, message: String?)
    }

    fun downloadAndInstall(downloadUrl: String, context: Context, callback: DownloadCallback?) {
        var apkFile: File? = null
        val apkName = "bgmonitor-update.apk"
        try {
            val cacheDir = context.cacheDir
            if (!cacheDir.exists()) cacheDir.mkdirs()
            apkFile = File(cacheDir, apkName)
            if (apkFile.exists()) apkFile.delete()

            val conn = URL(downloadUrl).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 10000
            conn.readTimeout = 30000
            conn.connect()

            val responseCode = conn.responseCode
            if (responseCode != 200) {
                val msg = "다운로드 실패 (HTTP $responseCode)"
                Log.e(TAG, msg)
                callback?.onComplete(false, msg)
                return
            }

            val totalSize = conn.contentLength.toLong()
            Log.d(TAG, "APK 다운로드 시작: $totalSize bytes")

            val inputStream: InputStream = conn.inputStream
            val fos = FileOutputStream(apkFile)

            val buffer = ByteArray(8192)
            var read: Int
            var downloaded: Long = 0
            var lastPercent = -1

            while (inputStream.read(buffer).also { read = it } != -1) {
                fos.write(buffer, 0, read)
                downloaded += read.toLong()
                if (totalSize > 0) {
                    val percent = (downloaded * 100 / totalSize).toInt()
                    if (percent != lastPercent) {
                        lastPercent = percent
                        callback?.onProgress(percent)
                    }
                }
            }

            inputStream.close()
            fos.close()
            conn.disconnect()

            callback?.onProgress(100)
            installApk(context, apkFile)
            callback?.onComplete(true, "다운로드 완료")

        } catch (e: Exception) {
            Log.e(TAG, "다운로드/설치 실패: ${e.message}")
            if (apkFile != null && apkFile.exists()) apkFile.delete()
            callback?.onComplete(false, "설치 실패: ${e.message}")
        }
    }

    private fun installApk(context: Context, apkFile: File) {
        val apkUri = FileProvider.getUriForFile(
            context,
            context.packageName + FILE_PROVIDER_AUTHORITY,
            apkFile
        )
        val intent = Intent(Intent.ACTION_VIEW)
        intent.setDataAndType(apkUri, "application/vnd.android.package-archive")
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}