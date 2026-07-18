package com.carbon.hardness

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

// ---- 라이트 팔레트 ----
private val BG = Color(0xFFF4F6F8)
private val SURF = Color(0xFFFFFFFF)
private val LINE = Color(0xFFE4E8ED)
private val TXT = Color(0xFF101828)
private val SUB = Color(0xFF667085)
private val ACCENT = Color(0xFF2E6BE6)
private val ACCENT_SOFT = Color(0xFFEAF1FE)
private val AMBER = Color(0xFFB45309)
private val AMBER_SOFT = Color(0xFFFEF3E2)
private val GREEN = Color(0xFF067647)
private val GREEN_SOFT = Color(0xFFEAF7EF)
private val RED = Color(0xFFB42318)
private val RED_SOFT = Color(0xFFFDECEA)
private val MONO = FontFamily.Monospace

class MainActivity : ComponentActivity() {

    private val vm: HardnessViewModel by viewModels()
    private var voice: VoiceEngine? = null

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        Notifications.ensureChannels(this)

        voice = VoiceEngine(
            context = this,
            onPartial = { vm.onPartial(it) },
            onFinal = { vm.onFinal(it) },
            onStateChange = { vm.onMicStateChanged(it) },
        ).also { it.init() }

        vm.requestMic = { on ->
            if (on) {
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
                    voice?.start()
            } else voice?.stop()
        }

        requestPerms()
        setContent { AppScreen(vm, onShare = { shareText(it) }) }
    }

    private fun requestPerms() {
        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        permLauncher.launch(perms.toTypedArray())
    }

    private fun shareText(text: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(send, "측정 이력 보내기"))
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (vm.voiceMode) { vm.toggleMic(); return true }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onPause() { super.onPause(); voice?.stop() }
    override fun onDestroy() { voice?.destroy(); super.onDestroy() }
}

@Composable
private fun AppScreen(vm: HardnessViewModel, onShare: (String) -> Unit) {
    var editSlot by remember { mutableStateOf(0) }
    var editName by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().background(BG).padding(horizontal = 14.dp)) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(top = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Header(vm, onShare, onEditName = { editName = true }, onHistory = { showHistory = true })
            StepStrip(vm)
            val runStage = vm.activeTimerStage
            val curIsTimer = vm.stages[vm.currentStage].kind == StageKind.TIMER
            val timerStage = runStage ?: if (curIsTimer) vm.currentStage else null
            if (timerStage != null) TimerCard(vm, timerStage)
            WeightsCard(vm) { editSlot = it }
            ResultCard(vm)
            Spacer(Modifier.height(4.dp))
        }
        VoiceBar(vm)
    }

    if (editSlot != 0) {
        DialEditor(
            slot = editSlot, slotName = vm.slots[editSlot - 1].name,
            initial = vm.weight(editSlot) ?: if (editSlot == 3) 1.5 else 45.0,
            onConfirm = { vm.setWeight(editSlot, it); editSlot = 0 },
            onDismiss = { editSlot = 0 }
        )
    }
    if (editName) {
        NameEditor(vm.sampleName, onConfirm = { vm.updateSampleName(it); editName = false }, onDismiss = { editName = false })
    }
    if (vm.confirmingReset) {
        ResetConfirm(onConfirm = { vm.executeReset() }, onDismiss = { vm.cancelReset() })
    }
    if (showHistory) {
        HistoryScreen(vm, onShare, onDismiss = { showHistory = false })
    }
}

// ---------- 헤더 ----------
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Header(
    vm: HardnessViewModel, onShare: (String) -> Unit,
    onEditName: () -> Unit, onHistory: () -> Unit
) {
    var shareMenu by remember { mutableStateOf(false) }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("활성탄 경도", color = TXT, fontSize = 20.sp, fontWeight = FontWeight.Black)
            Spacer(Modifier.weight(1f))
            ToolChip(
                text = if (vm.voiceMode) "음성" else "수동",
                active = vm.voiceMode,
                onClick = { vm.updateVoiceMode(!vm.voiceMode) }
            )
            Spacer(Modifier.width(8.dp))
            ToolChip(text = "초기화", active = false, onClick = { vm.requestReset() })
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(SURF)
                    .border(1.dp, LINE, RoundedCornerShape(10.dp))
                    .clickable { onEditName() }
                    .padding(horizontal = 12.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("시료", color = SUB, fontSize = 13.sp)
                Spacer(Modifier.width(8.dp))
                Text(
                    vm.sampleName.ifBlank { "이름 입력" },
                    color = if (vm.sampleName.isBlank()) SUB else TXT,
                    fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1
                )
            }
            Spacer(Modifier.width(8.dp))
            ToolChip(text = "이력", active = false, onClick = onHistory)
            Spacer(Modifier.width(8.dp))
            Box {
                Box(
                    Modifier.clip(RoundedCornerShape(12.dp)).background(ACCENT_SOFT)
                        .combinedClickable(
                            onClick = { onShare(History.toTsv(vm.history.today())) },
                            onLongClick = { shareMenu = true }
                        )
                        .padding(horizontal = 14.dp, vertical = 9.dp)
                ) {
                    Text("📤", fontSize = 14.sp)
                }
                DropdownMenu(expanded = shareMenu, onDismissRequest = { shareMenu = false }) {
                    DropdownMenuItem(text = { Text("오늘 이력 보내기") },
                        onClick = { shareMenu = false; onShare(History.toTsv(vm.history.today())) })
                    DropdownMenuItem(text = { Text("최근 7일 보내기") },
                        onClick = { shareMenu = false; onShare(History.toTsv(vm.history.lastDays(7))) })
                    DropdownMenuItem(text = { Text("전체 이력 보내기") },
                        onClick = { shareMenu = false; onShare(History.toTsv(vm.history.all())) })
                }
            }
        }
    }
}

@Composable
private fun ToolChip(text: String, active: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(12.dp))
            .background(if (active) ACCENT else SURF)
            .border(1.dp, if (active) ACCENT else LINE, RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 9.dp)
    ) {
        Text(text, color = if (active) Color.White else SUB, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
}

// ---------- 진행바 ----------
@Composable
private fun StepStrip(vm: HardnessViewModel) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        vm.stages.forEach { s ->
            val active = s.index == vm.currentStage
            val done = s.kind == StageKind.TIMER && vm.timerStatus(s.index) == 2
            val running = s.kind == StageKind.TIMER && vm.timerStatus(s.index) == 1
            val borderColor = when { running -> AMBER; done || active -> ACCENT; else -> LINE }
            val bg = when { running -> AMBER_SOFT; done -> GREEN_SOFT; else -> SURF }
            Column(
                Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(bg)
                    .border(if (active) 2.dp else 1.dp, borderColor, RoundedCornerShape(12.dp))
                    .clickable { vm.goStage(s.index) }
                    .padding(vertical = 7.dp, horizontal = 2.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(s.short, color = if (active) TXT else SUB, fontSize = 11.sp,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Normal, maxLines = 1)
                if (s.kind == StageKind.TIMER) {
                    when {
                        running -> {
                            Text(
                                "${TimeFmt.clock(vm.timerStart(s.index))}→${TimeFmt.clock(vm.timerEnd(s.index))}",
                                color = AMBER, fontFamily = MONO, fontSize = 9.sp,
                                fontWeight = FontWeight.Bold, maxLines = 1
                            )
                            Text("${vm.elapsedMin(s.index)}분 경과", color = SUB, fontSize = 9.sp, maxLines = 1)
                        }
                        done -> Text("✓ ${TimeFmt.clock(vm.timerEnd(s.index))}", color = GREEN,
                            fontFamily = MONO, fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                        else -> Text("${s.minutes}′", color = SUB, fontSize = 10.sp, maxLines = 1)
                    }
                } else {
                    val filled = s.weighSlots.count { vm.weight(it) != null }
                    val total = s.weighSlots.size
                    val txt = when {
                        total == 1 && filled == 1 -> vm.fmt(vm.weight(s.weighSlots[0])!!)
                        filled == total && total > 0 -> "✓"
                        filled > 0 -> "$filled/$total"
                        else -> "—"
                    }
                    Text(txt, color = if (filled == total) GREEN else SUB, fontFamily = MONO,
                        fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                }
            }
        }
    }
}

// ---------- 타이머 ----------
@Composable
private fun TimerCard(vm: HardnessViewModel, stage: Int) {
    val s = vm.stages[stage]
    val st = vm.timerStatus(stage)
    Card(
        colors = CardDefaults.cardColors(containerColor = SURF),
        shape = RoundedCornerShape(20.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, LINE)
    ) {
        Column(Modifier.fillMaxWidth().padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(s.title, color = SUB, fontSize = 14.sp)
                Spacer(Modifier.weight(1f))
                val (tag, tagBg, tagFg) = when (st) {
                    1 -> Triple("진행 중", AMBER_SOFT, AMBER)
                    2 -> Triple("완료", GREEN_SOFT, GREEN)
                    else -> Triple("${s.minutes}분", BG, SUB)
                }
                Box(Modifier.clip(RoundedCornerShape(999.dp)).background(tagBg).padding(horizontal = 10.dp, vertical = 4.dp)) {
                    Text(tag, color = tagFg, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
            val remain = if (st == 1) vm.remainingSec(stage) else s.minutes * 60
            Text(
                TimeFmt.mmss(remain),
                color = if (st == 1) ACCENT else TXT, fontFamily = MONO,
                fontSize = 64.sp, fontWeight = FontWeight.Black
            )
            if (st == 1) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("시작 ${TimeFmt.clock(vm.timerStart(stage))}", color = SUB, fontSize = 15.sp)
                    Text("→", color = SUB, fontSize = 15.sp)
                    Text("완료 ${TimeFmt.clock(vm.timerEnd(stage))}", color = ACCENT, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                }
            } else {
                Text("시작하면 완료 시각이 표시됩니다", color = SUB, fontSize = 13.sp)
            }
            Spacer(Modifier.height(14.dp))
            when (st) {
                1 -> BigButton("정지", RED, Color.White) { vm.stopTimer(stage, byUser = true) }
                2 -> BigButton("다시 시작", BG, TXT) { vm.startTimer(stage) }
                else -> BigButton("시작", ACCENT, Color.White) { vm.startTimer(stage) }
            }
            Spacer(Modifier.height(6.dp))
            Text("음성: \"${s.short} 시작\" · \"정지\"", color = SUB, fontSize = 13.sp)
        }
    }
}

// ---------- 무게 ----------
@Composable
private fun WeightsCard(vm: HardnessViewModel, onEdit: (Int) -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = SURF),
        shape = RoundedCornerShape(20.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, LINE)
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            vm.slots.forEach { slot ->
                val v = vm.weight(slot.no)
                val pending = vm.pendingSlot == slot.no
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                        .background(if (pending) ACCENT_SOFT else BG)
                        .border(if (pending) 2.dp else 0.dp, if (pending) ACCENT else Color.Transparent, RoundedCornerShape(14.dp))
                        .clickable { onEdit(slot.no) }
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier.size(30.dp).clip(CircleShape)
                            .background(if (pending) ACCENT else SURF)
                            .border(1.dp, if (pending) ACCENT else LINE, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("${slot.no}", color = if (pending) Color.White else ACCENT,
                            fontFamily = MONO, fontWeight = FontWeight.Black, fontSize = 15.sp)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(slot.name, color = TXT, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Text(slot.detail, color = SUB, fontSize = 12.sp)
                    }
                    Text(
                        v?.let { vm.fmt(it) } ?: "—",
                        color = if (pending) ACCENT else if (v == null) SUB else TXT,
                        fontFamily = MONO, fontSize = 24.sp, fontWeight = FontWeight.Black
                    )
                    Text(" g", color = SUB, fontSize = 14.sp)
                }
            }
        }
    }
}

// ---------- 결과 ----------
@Composable
private fun ResultCard(vm: HardnessViewModel) {
    val ok = vm.massOk
    val h = vm.hardness
    val err = vm.massErrorPct
    val bg = when (ok) { true -> GREEN_SOFT; false -> RED_SOFT; null -> SURF }
    val ln = when (ok) { true -> GREEN; false -> RED; null -> LINE }
    Card(
        colors = CardDefaults.cardColors(containerColor = bg),
        shape = RoundedCornerShape(20.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, ln)
    ) {
        Column(Modifier.fillMaxWidth().padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text("경도", color = SUB, fontSize = 13.sp)
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            h?.let { vm.fmt(it) } ?: "—",
                            color = if (h != null) TXT else SUB,
                            fontFamily = MONO, fontSize = 44.sp, fontWeight = FontWeight.Black
                        )
                        Text(" %", color = SUB, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    }
                    Text("무거운 ÷ 초기 × 100", color = SUB, fontSize = 11.sp)
                }
                Spacer(Modifier.width(18.dp))
                Column(Modifier.weight(1f)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("질량오차", color = SUB, fontSize = 13.sp)
                        Text(
                            err?.let { "${vm.fmt(it)}%" } ?: "—",
                            color = when (ok) { true -> GREEN; false -> RED; null -> SUB },
                            fontFamily = MONO, fontSize = 16.sp, fontWeight = FontWeight.Black
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    CmpRow("무거운+가벼운", vm.sumLightHeavy?.let { "${vm.fmt(it)} g" } ?: "—")
                    CmpRow("초기 M₀", vm.weight(1)?.let { "${vm.fmt(it)} g" } ?: "—")
                    if (ok != null) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            if (ok) "✓ 정상 (오차 ≤2%)" else "✗ 재측정 권장 (>2%)",
                            color = if (ok) GREEN else RED, fontSize = 12.sp, fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
            if (vm.savedRun) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "✓ 이력에 저장됨 — [이력]에서 확인 · 📤로 내보내기",
                    color = GREEN, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun CmpRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = SUB, fontSize = 12.sp)
        Text(value, color = TXT, fontFamily = MONO, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}

// ---------- 음성 바 ----------
@Composable
private fun VoiceBar(vm: HardnessViewModel) {
    Column(Modifier.padding(vertical = 10.dp)) {
        if (vm.pendingSlot != null) {
            val slot = vm.pendingSlot!!
            Card(
                colors = CardDefaults.cardColors(containerColor = SURF),
                shape = RoundedCornerShape(18.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, ACCENT)
            ) {
                Column(Modifier.fillMaxWidth().padding(14.dp)) {
                    Text(
                        "${slot}번 ${vm.slots[slot - 1].name} — 맞나요?",
                        color = SUB, fontSize = 13.sp, textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        "${vm.weight(slot)?.let { vm.fmt(it) } ?: "—"} g",
                        color = ACCENT, fontFamily = MONO, fontSize = 30.sp, fontWeight = FontWeight.Black,
                        textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Button(
                            onClick = { vm.rejectPending() },
                            modifier = Modifier.weight(1f).height(64.dp),
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = BG)
                        ) { Text("아니", color = TXT, fontSize = 22.sp, fontWeight = FontWeight.Black) }
                        Button(
                            onClick = { vm.confirmPending() },
                            modifier = Modifier.weight(1f).height(64.dp),
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = ACCENT)
                        ) { Text("응", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Black) }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        // 큰 마이크 바 (팔꿈치용)
        when {
            !vm.voiceMode -> {
                Card(
                    colors = CardDefaults.cardColors(containerColor = BG),
                    shape = RoundedCornerShape(18.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, LINE)
                ) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 20.dp)) {
                        Text("수동 모드", color = SUB, fontSize = 20.sp, fontWeight = FontWeight.Black)
                        Text("음성 꺼짐 · 우측 상단 [수동]을 눌러 음성 켜기", color = SUB, fontSize = 13.sp)
                    }
                }
            }
            vm.micOn -> {
                val pulse by rememberInfiniteTransition(label = "mic").animateFloat(
                    initialValue = 1f, targetValue = 1.25f,
                    animationSpec = infiniteRepeatable(tween(600), RepeatMode.Reverse), label = "mic"
                )
                Card(
                    onClick = { vm.toggleMic() },
                    colors = CardDefaults.cardColors(containerColor = ACCENT),
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 96.dp).padding(horizontal = 20.dp, vertical = 20.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("🎙️", fontSize = 34.sp, modifier = Modifier.scale(pulse))
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text("듣는 중", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Black)
                            Text(
                                if (vm.heardText.isBlank()) vm.status else "\"${vm.heardText}\"",
                                color = Color(0xFFD8E4FB), fontSize = 14.sp, maxLines = 1
                            )
                        }
                        Text("끄기", color = Color(0xFFD8E4FB), fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
            else -> {
                Card(
                    onClick = { vm.toggleMic() },
                    colors = CardDefaults.cardColors(containerColor = SURF),
                    shape = RoundedCornerShape(20.dp),
                    border = androidx.compose.foundation.BorderStroke(2.dp, ACCENT)
                ) {
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 96.dp).padding(horizontal = 20.dp, vertical = 20.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("🎙️", fontSize = 34.sp)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text("마이크 켜기", color = TXT, fontSize = 24.sp, fontWeight = FontWeight.Black)
                            Text("여기 또는 볼륨키 · ${vm.status}", color = SUB, fontSize = 13.sp, maxLines = 2)
                        }
                    }
                }
            }
        }
    }
}

// ---------- 이력 화면 ----------
@Composable
private fun HistoryScreen(vm: HardnessViewModel, onShare: (String) -> Unit, onDismiss: () -> Unit) {
    val records = remember { vm.history.all() }
    val df = remember { SimpleDateFormat("M/d (E) HH:mm", Locale.KOREA) }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            colors = CardDefaults.cardColors(containerColor = SURF),
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier.fillMaxWidth(0.94f).fillMaxHeight(0.88f)
        ) {
            Column(Modifier.fillMaxSize().padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("측정 이력", color = TXT, fontSize = 19.sp, fontWeight = FontWeight.Black)
                    Spacer(Modifier.width(8.dp))
                    Text("${records.size}건", color = SUB, fontSize = 14.sp)
                    Spacer(Modifier.weight(1f))
                    ToolChip(text = "닫기", active = false, onClick = onDismiss)
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ToolChip(text = "오늘 📤", active = false) { onShare(History.toTsv(vm.history.today())) }
                    ToolChip(text = "7일 📤", active = false) { onShare(History.toTsv(vm.history.lastDays(7))) }
                    ToolChip(text = "전체 📤", active = false) { onShare(History.toTsv(records)) }
                }
                Spacer(Modifier.height(10.dp))
                if (records.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            "아직 이력이 없어요.\n무게 3개를 모두 확정하면 자동으로 저장됩니다.",
                            color = SUB, fontSize = 14.sp, textAlign = TextAlign.Center
                        )
                    }
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(records) { r ->
                            val ok = r.errPct <= 2.0
                            Column(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                                    .background(BG).padding(12.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        r.name.ifBlank { "(이름 없음)" },
                                        color = TXT, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Text(df.format(Date(r.ts)), color = SUB, fontSize = 12.sp)
                                }
                                Spacer(Modifier.height(6.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        "초기 ${f2(r.w1)} · 무 ${f2(r.w2)} · 가 ${f2(r.w3)}",
                                        color = SUB, fontFamily = MONO, fontSize = 13.sp,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Box(
                                        Modifier.clip(RoundedCornerShape(999.dp))
                                            .background(if (ok) GREEN_SOFT else RED_SOFT)
                                            .padding(horizontal = 10.dp, vertical = 4.dp)
                                    ) {
                                        Text(
                                            "${f2(r.hardness)}%",
                                            color = if (ok) GREEN else RED,
                                            fontFamily = MONO, fontSize = 14.sp, fontWeight = FontWeight.Black
                                        )
                                    }
                                }
                                Text("질량오차 ${f2(r.errPct)}%", color = SUB, fontSize = 11.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---------- 다이얼 (드래그 + 직접입력 + 미세버튼) ----------
@Composable
private fun DialEditor(
    slot: Int, slotName: String, initial: Double,
    onConfirm: (Double) -> Unit, onDismiss: () -> Unit
) {
    var raw by remember { mutableStateOf(initial) }        // 연속값 (드래그용)
    var typing by remember { mutableStateOf(false) }
    var typed by remember { mutableStateOf("") }
    val shown = round2(raw)

    Dialog(onDismissRequest = onDismiss) {
        Card(colors = CardDefaults.cardColors(containerColor = SURF), shape = RoundedCornerShape(22.dp)) {
            Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("$slot 번 · $slotName", color = SUB, fontSize = 14.sp)

                if (typing) {
                    OutlinedTextField(
                        value = typed,
                        onValueChange = { typed = it },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        textStyle = androidx.compose.ui.text.TextStyle(
                            fontFamily = MONO, fontSize = 34.sp, fontWeight = FontWeight.Black,
                            textAlign = TextAlign.Center
                        ),
                        placeholder = { Text("45.34") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = {
                            typed.replace(",", ".").toDoubleOrNull()?.let { raw = clampD(it) }
                            typing = false
                        },
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = ACCENT_SOFT)
                    ) { Text("입력 적용", color = ACCENT, fontSize = 15.sp, fontWeight = FontWeight.Bold) }
                } else {
                    Text(
                        "${fmt(shown)} g",
                        color = TXT, fontFamily = MONO, fontSize = 46.sp, fontWeight = FontWeight.Black,
                        modifier = Modifier.clickable { typed = fmt(shown); typing = true }
                    )
                    Text("숫자를 누르면 직접 입력", color = SUB, fontSize = 11.sp)
                }

                Spacer(Modifier.height(12.dp))

                // 드래그 다이얼 (좌우로 문지르면 연속으로 변함)
                DragDial(value = raw, onChange = { raw = clampD(it) })
                Text("좌우로 드래그 · 1칸 = 0.01 g", color = SUB, fontSize = 11.sp)

                Spacer(Modifier.height(12.dp))

                // 미세조정 버튼 (기존 기능 유지)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FineBtn("−1") { raw = clampD(round2(raw) - 1.0) }
                    FineBtn("−0.1") { raw = clampD(round2(raw) - 0.1) }
                    FineBtn("−0.01") { raw = clampD(round2(raw) - 0.01) }
                    FineBtn("+0.01") { raw = clampD(round2(raw) + 0.01) }
                    FineBtn("+0.1") { raw = clampD(round2(raw) + 0.1) }
                    FineBtn("+1") { raw = clampD(round2(raw) + 1.0) }
                }

                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = onDismiss, modifier = Modifier.weight(1f).height(56.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = BG)
                    ) { Text("취소", color = TXT, fontSize = 18.sp) }
                    Button(
                        onClick = { onConfirm(round2(raw)) }, modifier = Modifier.weight(1f).height(56.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = ACCENT)
                    ) { Text("기록", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold) }
                }
            }
        }
    }
}

/** 눈금이 손가락을 따라 움직이는 가로 다이얼 */
@Composable
private fun DragDial(value: Double, onChange: (Double) -> Unit) {
    val curValue by rememberUpdatedState(value)
    val curOnChange by rememberUpdatedState(onChange)
    val density = LocalDensity.current
    val tickSpacingPx = with(density) { 14.dp.toPx() }   // 0.01g 당 픽셀

    Box(
        Modifier.fillMaxWidth().height(84.dp)
            .clip(RoundedCornerShape(14.dp)).background(BG)
            .border(1.dp, LINE, RoundedCornerShape(14.dp))
            .pointerInput(Unit) {
                detectHorizontalDragGestures { change, dragAmount ->
                    change.consume()
                    curOnChange(curValue - dragAmount / tickSpacingPx * 0.01)
                }
            }
    ) {
        Canvas(Modifier.matchParentSize()) {
            val cx = size.width / 2f
            val cy = size.height
            val t = curValue * 100.0                     // 0.01 단위 연속 위치
            val first = (t - cx / tickSpacingPx).toInt() - 1
            val last = (t + cx / tickSpacingPx).toInt() + 1
            for (k in first..last) {
                if (k < 0) continue
                val x = cx + ((k - t) * tickSpacingPx).toFloat()
                if (x < 0 || x > size.width) continue
                val major = k % 10 == 0
                val h = if (major) size.height * 0.62f else size.height * 0.34f
                drawLine(
                    color = if (major) Color(0xFF98A2B3) else Color(0xFFD0D5DD),
                    start = Offset(x, cy),
                    end = Offset(x, cy - h),
                    strokeWidth = if (major) 3f else 2f
                )
            }
            // 중앙 지시선
            drawLine(
                color = Color(0xFF2E6BE6),
                start = Offset(cx, cy),
                end = Offset(cx, 6f),
                strokeWidth = 5f
            )
        }
    }
}

@Composable
private fun FineBtn(label: String, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(10.dp)).background(ACCENT_SOFT)
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 12.dp)
    ) {
        Text(label, color = ACCENT, fontFamily = MONO, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}

// ---------- 시료명 ----------
@Composable
private fun NameEditor(current: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(current) }
    Dialog(onDismissRequest = onDismiss) {
        Card(colors = CardDefaults.cardColors(containerColor = SURF), shape = RoundedCornerShape(22.dp)) {
            Column(Modifier.padding(20.dp)) {
                Text("시료 이름", color = TXT, fontSize = 17.sp, fontWeight = FontWeight.Black)
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = text, onValueChange = { text = it },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("예: A-07") }
                )
                Spacer(Modifier.height(14.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = onDismiss, modifier = Modifier.weight(1f).height(52.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = BG)
                    ) { Text("취소", color = TXT, fontSize = 16.sp) }
                    Button(
                        onClick = { onConfirm(text) }, modifier = Modifier.weight(1f).height(52.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = ACCENT)
                    ) { Text("저장", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold) }
                }
            }
        }
    }
}

// ---------- 초기화 확인 ----------
@Composable
private fun ResetConfirm(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Card(colors = CardDefaults.cardColors(containerColor = SURF), shape = RoundedCornerShape(22.dp)) {
            Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("모든 값을 지울까요?", color = TXT, fontSize = 18.sp, fontWeight = FontWeight.Black)
                Spacer(Modifier.height(6.dp))
                Text("무게·타이머가 모두 지워지고 새 측정을 시작합니다.\n저장된 이력은 그대로 남아요.",
                    color = SUB, fontSize = 14.sp, textAlign = TextAlign.Center)
                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = onDismiss, modifier = Modifier.weight(1f).height(56.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = BG)
                    ) { Text("취소", color = TXT, fontSize = 17.sp) }
                    Button(
                        onClick = onConfirm, modifier = Modifier.weight(1f).height(56.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = RED)
                    ) { Text("초기화", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold) }
                }
            }
        }
    }
}

@Composable
private fun BigButton(text: String, bg: Color, fg: Color, onClick: () -> Unit) {
    Button(
        onClick = onClick, modifier = Modifier.fillMaxWidth().height(72.dp),
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(containerColor = bg)
    ) { Text(text, color = fg, fontSize = 24.sp, fontWeight = FontWeight.Black) }
}

private fun clampD(v: Double) = max(0.0, min(200.0, v))
private fun round2(v: Double) = Math.round(v * 100.0) / 100.0
private fun fmt(v: Double) = String.format(Locale.US, "%.2f", v)
private fun f2(v: Double) = String.format(Locale.US, "%.2f", v)
