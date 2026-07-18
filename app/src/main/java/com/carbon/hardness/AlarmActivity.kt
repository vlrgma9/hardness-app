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

/** 타이머 완료 시 잠금화면 위에도 뜨는 진동 알람. 음성 "정지" / 볼륨키 / 큰 버튼으로 종료 */
class AlarmActivity : ComponentActivity() {

    private var voice: VoiceEngine? = null

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

        voice = VoiceEngine(
            context = this,
            onPartial = {},
            onFinal = { text ->
                if (VoiceParser.detectCommand(text) == Command.STOP_TIMER) dismiss()
            }
        ).also { it.init(); it.start() }

        setContent {
            var blink by remember { mutableStateOf(true) }
            LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(600); blink = !blink } }
            Column(
                Modifier.fillMaxSize()
                    .background(if (blink) Color(0xFF14532D) else Color(0xFF0C3A1E))
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text("📳", fontSize = 84.sp)
                Spacer(Modifier.height(12.dp))
                Text("$label 완료!", color = Color.White, fontSize = 42.sp,
                    fontWeight = FontWeight.Black, textAlign = TextAlign.Center)
                Spacer(Modifier.height(10.dp))
                Text("활성탄을 확인하세요", color = Color(0xFFB6E5C4), fontSize = 20.sp)
                Spacer(Modifier.height(44.dp))
                Button(
                    onClick = { dismiss() },
                    modifier = Modifier.fillMaxWidth().height(110.dp),
                    shape = RoundedCornerShape(22.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White)
                ) {
                    Text("정지", color = Color(0xFF14532D), fontSize = 40.sp, fontWeight = FontWeight.Black)
                }
                Spacer(Modifier.height(18.dp))
                Text("\"정지\" 라고 말하거나 볼륨 버튼을 눌러도 꺼집니다",
                    color = Color(0xFFD8F3DF), fontSize = 15.sp, textAlign = TextAlign.Center)
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

    override fun onDestroy() { voice?.destroy(); super.onDestroy() }
}
