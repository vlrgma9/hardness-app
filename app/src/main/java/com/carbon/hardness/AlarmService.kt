package com.carbon.hardness

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/** 타이머 완료: 소리 없이 진동만 반복. 확인(정지)하면 멈춤. */
class AlarmService : Service() {

    private var vibrator: Vibrator? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        val label = intent?.getStringExtra(AlarmScheduler.EXTRA_LABEL) ?: "타이머"
        val stage = intent?.getIntExtra(AlarmScheduler.EXTRA_STAGE, -1) ?: -1

        // 완료로 표시 (확인 전이라도 시간상 완료됨)
        if (stage >= 0) {
            val p = Prefs(this)
            p.setTimer(stage, 2, p.timerStart(stage), p.timerEnd(stage))
        }

        startForegroundAlarm(label)
        startVibration()
        return START_STICKY
    }

    private fun startForegroundAlarm(label: String) {
        Notifications.ensureChannels(this)

        val fullScreen = PendingIntent.getActivity(
            this, 1,
            Intent(this, AlarmActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                .putExtra(AlarmScheduler.EXTRA_LABEL, label),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopPi = PendingIntent.getService(
            this, 2,
            Intent(this, AlarmService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notif: Notification = Notification.Builder(this, Notifications.CH_ALARM)
            .setContentTitle("✔ $label 완료")
            .setContentText("탭하거나 \"정지\" · 여기서 정지")
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setCategory(Notification.CATEGORY_ALARM)
            .setOngoing(true)
            .setFullScreenIntent(fullScreen, true)
            .setContentIntent(fullScreen)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "정지", stopPi)
            .build()

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun startVibration() {
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        val pattern = longArrayOf(0, 800, 600)   // 반복
        try {
            vibrator?.vibrate(VibrationEffect.createWaveform(pattern, 0))
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        try { vibrator?.cancel() } catch (_: Exception) {}
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "com.carbon.hardness.STOP_ALARM"
        private const val NOTIF_ID = 42
    }
}
