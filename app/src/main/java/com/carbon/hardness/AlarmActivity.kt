package com.carbon.hardness

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 타이머 완료 진동 알람 (잠금화면 위 표시). 버튼/볼륨키로 끄기 — 사무실이라 음성은 안 씀 */
class AlarmActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true); setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val label = intent.getStringExtra(AlarmScheduler.EXTRA_LABEL) ?: "타이머"

        setContent {
            var blink by remember { mutableStateOf(true) }
            LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(700); blink = !blink } }
            Column(
                Modifier.fillMaxSize()
                    .background(if (blink) Color(0xFF2E6BE6) else Color(0xFF1D4FB8))
                    .padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text("📳", fontSize = 80.sp)
                Spacer(Modifier.height(14.dp))
                Text(
                    "$label 완료!",
                    color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.Black,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(10.dp))
                Text("실험실로 이동하세요", color = Color(0xFFD8E4FB), fontSize = 19.sp)
                Spacer(Modifier.height(46.dp))
                Button(
                    onClick = { dismiss() },
                    modifier = Modifier.fillMaxWidth().height(104.dp),
                    shape = RoundedCornerShape(24.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White)
                ) {
                    Text("확인", color = Color(0xFF1D4FB8), fontSize = 36.sp, fontWeight = FontWeight.Black)
                }
                Spacer(Modifier.height(16.dp))
                Text("볼륨 버튼을 눌러도 꺼집니다", color = Color(0xFFD8E4FB), fontSize = 14.sp)
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            dismiss(); return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun dismiss() {
        try {
            startService(Intent(this, AlarmService::class.java).setAction(AlarmService.ACTION_STOP))
        } catch (_: Exception) {}
        finish()
    }
}
