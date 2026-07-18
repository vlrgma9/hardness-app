package com.carbon.hardness

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

object Notifications {
    const val CH_ONGOING = "timer_ongoing"
    const val CH_ALARM = "hardness_alarm"
    const val ID_ONGOING = 100

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_ONGOING, "타이머 진행", NotificationManager.IMPORTANCE_LOW).apply {
                description = "진행 중인 타이머의 남은 시간 · 완료 시각"
                setShowBadge(false)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_ALARM, "타이머 완료 알림", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "타이머 완료 시 진동 알림"
                setSound(null, null)
                enableVibration(false) // 진동은 서비스가 직접 제어
            }
        )
    }

    /** 진행 중인 타이머의 라이브 카운트다운(앱이 꺼져도 시스템이 계속 표시) */
    fun showCountdown(context: Context, title: String, endMillis: Long) {
        ensureChannels(context)
        val openApp = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val finClock = TimeFmt.clock(endMillis)
        val notif = Notification.Builder(context, CH_ONGOING)
            .setSmallIcon(android.R.drawable.ic_menu_recent_history)
            .setContentTitle("$title 진행 중")
            .setContentText("완료 $finClock")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            .setWhen(endMillis)
            .setContentIntent(openApp)
            .build()
        context.getSystemService(NotificationManager::class.java)
            .notify(ID_ONGOING, notif)
    }

    fun cancelCountdown(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(ID_ONGOING)
    }
}
