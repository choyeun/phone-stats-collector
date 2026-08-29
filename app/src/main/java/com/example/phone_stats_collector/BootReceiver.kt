package com.example.phone_stats_collector

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.i("BootReceiver", "부팅 완료 → API 서비스 시작")
            val serviceIntent = Intent(context, StatsApiService::class.java)
            context.startForegroundService(serviceIntent)
        }
    }
}