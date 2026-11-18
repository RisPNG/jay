package com.bnyro.clock.util.services

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat

class TimerAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.e("receiver", "received timer")
        val serviceIntent = Intent(context, TimerService::class.java).apply {
            action = TimerService.ACTION_TIMER_EXPIRED
            putExtras(intent)
        }
        ContextCompat.startForegroundService(context, serviceIntent)
    }
}