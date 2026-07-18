package com.carbon.hardness

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/** 타이머 완료 시각이 되면 호출 → 진동 알림 서비스 시작 */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val label = intent.getStringExtra(AlarmScheduler.EXTRA_LABEL) ?: "타이머"
        val stage = intent.getIntExtra(AlarmScheduler.EXTRA_STAGE, -1)
        Notifications.cancelCountdown(context)
        val svc = Intent(context, AlarmService::class.java).apply {
            putExtra(AlarmScheduler.EXTRA_LABEL, label)
            putExtra(AlarmScheduler.EXTRA_STAGE, stage)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(svc)
        } else {
            context.startService(svc)
        }
    }
}
