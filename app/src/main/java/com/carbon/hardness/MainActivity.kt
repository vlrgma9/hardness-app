package com.carbon.hardness

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlin.math.max
import kotlin.math.min

private val BG = Color(0xFF0E120D)
private val SURF = Color(0xFF181F17)
private val SURF2 = Color(0xFF202A1E)
private val LINE = Color(0xFF2B382A)
private val TXT = Color(0xFFEAF2E9)
private val SUB = Color(0xFF90A389)
private val ACCENT = Color(0xFF57C777)
private val ACCENT_INK = Color(0xFF08210F)
private val AMBER = Color(0xFFF2B134)
private val RED = Color(0xFFFF5A47)
private val OKBG = Color(0xFF1E4D2B)
private val OKLINE = Color(0xFF2F6F42)
private val BADBG = Color(0xFF5A1F1A)
private val BADLINE = Color(0xFF8A3227)
private val MONO = FontFamily.Monospace

class MainActivity : ComponentActivity() {

    private val vm: HardnessViewModel by viewModels()
    private var voice: VoiceEngine? = null

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result[Manifest.permission.RECORD_AUDIO] == true) voice?.start()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        Notifications.ensureChannels(this)

        voice = VoiceEngine(
            context = this,
            onPartial = { vm.onPartial(it) },
            onFinal = { vm.onFinal(it) },
        ).also { it.init() }
        vm.nudgeListen = { voice?.nudge() }

        requestPerms()
        setContent { AppScreen(vm, onTapTalk = { voice?.nudge() }) }
    }

    private fun requestPerms() {
        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        permLauncher.launch(perms.toTypedArray())
    }

    override fun onResume() {
        super.onResume()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
            voice?.start()
    }

    override fun onPause() {
        super.onPause()
        voice?.stop()
    }

    override fun onDestroy() { voice?.destroy(); super.onDestroy() }
}

@Composable
private fun AppScreen(vm: HardnessViewModel, onTapTalk: () -> Unit) {
    var editSlot by remember { mutableStateOf(0) }

    Column(Modifier.fillMaxSize().background(BG).padding(horizontal = 12.dp)) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(top = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Header()
            StepStrip(vm)
            val runStage = vm.activeTimerStage
            val curIsTimer = vm.stages[vm.currentStage].kind == StageKind.TIMER
            val timerStage = runStage ?: if (curIsTimer) vm.currentStage else null
            if (timerStage != null) TimerCard(vm, timerStage)
            WeightsCard(vm) { editSlot = it }
            VerifyCard(vm)
            Spacer(Modifier.height(4.dp))
        }
        VoiceBar(vm, onTapTalk)
    }

    if (editSlot != 0) {
        DialEditor(
            slot = editSlot,
            slotName = vm.slots[editSlot - 1].name,
            initial = vm.weight(editSlot) ?: 45.0,
            onConfirm = { vm.setWeight(editSlot, it); editSlot = 0 },
            onDismiss = { editSlot = 0 }
        )
    }
}

@Composable
private fun Header() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("활성탄 경도", color = TXT, fontSize = 16.sp, fontWeight = FontWeight.Black)
        Spacer(Modifier.weight(1f))
        Text("경도측정 · 핸즈프리", color = SUB, fontSize = 12.sp)
    }
}

@Composable
private fun StepStrip(vm: HardnessViewModel) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        vm.stages.forEach { s ->
            val active = s.index == vm.currentStage
            val done = s.kind == StageKind.TIMER && vm.timerStatus(s.index) == 2
            val running = s.kind == StageKind.TIMER && vm.timerStatus(s.index) == 1
            val border = when { running -> AMBER; done -> ACCENT; active -> ACCENT; else -> LINE }
            Column(
                Modifier.weight(1f).clip(RoundedCornerShape(12.dp))
                    .background(if (running) SURF2 else SURF)
                    .outline(active, border).clickable { vm.goStage(s.index) }
                    .padding(vertical = 7.dp, horizontal = 2.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(s.short, color = SUB, fontSize = 11.sp, maxLines = 1)
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
                        done -> Text(
                            "✓ ${TimeFmt.clock(vm.timerEnd(s.index))}",
                            color = ACCENT, fontFamily = MONO, fontSize = 10.sp,
                            fontWeight = FontWeight.Bold, maxLines = 1
                        )
                        else -> Text("${s.minutes}′ 대기", color = SUB, fontSize = 10.sp, maxLines = 1)
                    }
                } else {
                    val filled = s.weighSlots.count { vm.weight(it) != null }
                    val total = s.weighSlots.size
                    val txt = when {
                        total == 1 && filled == 1 -> vm.fmt(vm.weight(s.weighSlots[0])!!)
                        filled == total && total > 0 -> "✓ 완료"
                        filled > 0 -> "$filled/$total"
                        else -> "계량"
                    }
                    Text(
                        txt, color = if (filled == total) ACCENT else SUB,
                        fontFamily = MONO, fontSize = 10.sp,
                        fontWeight = FontWeight.Bold, maxLines = 1
                    )
                }
            }
        }
    }
}

private fun Modifier.outline(active: Boolean, c: Color) =
    this.border(
        width = if (active) 2.dp else 1.dp, color = c, shape = RoundedCornerShape(12.dp)
    )

@Composable
private fun TimerCard(vm: HardnessViewModel, stage: Int) {
    val s = vm.stages[stage]
    val st = vm.timerStatus(stage)
    Card(colors = CardDefaults.cardColors(containerColor = SURF), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(s.title, color = SUB, fontSize = 14.sp)
                Spacer(Modifier.weight(1f))
                val tag = when (st) { 1 -> "진행 중"; 2 -> "완료"; else -> "${s.minutes}분" }
                val tagBg = when (st) { 1 -> AMBER; 2 -> ACCENT; else -> SURF2 }
                val tagFg = when (st) { 1, 2 -> Color(0xFF231A02); else -> SUB }
                Box(Modifier.clip(RoundedCornerShape(999.dp)).background(tagBg).padding(horizontal = 9.dp, vertical = 3.dp)) {
                    Text(tag, color = tagFg, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
            val remain = if (st == 1) vm.remainingSec(stage) else s.minutes * 60
            Text(
                TimeFmt.mmss(remain),
                color = if (st == 1) AMBER else TXT, fontFamily = MONO,
                fontSize = 62.sp, fontWeight = FontWeight.Black
            )
            if (st == 1) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("시작 ${TimeFmt.clock(vm.timers[stage]!!.start)}", color = SUB, fontSize = 15.sp)
                    Text("→", color = SUB, fontSize = 15.sp)
                    Text("완료 ${TimeFmt.clock(vm.timerEnd(stage))}", color = ACCENT, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                }
            } else {
                Text("시작하면 완료 시각이 표시됩니다", color = SUB, fontSize = 14.sp)
            }
            Spacer(Modifier.height(12.dp))
            when (st) {
                1 -> BigButton("■  정지", RED, Color.White) { vm.stopTimer(stage, byUser = true) }
                2 -> BigButton("↺  다시 시작", SURF2, TXT) { vm.startTimer(stage) }
                else -> BigButton("▶  시작", ACCENT, ACCENT_INK) { vm.startTimer(stage) }
            }
            Spacer(Modifier.height(6.dp))
            Text("음성: \"시작\" · \"정지\"", color = SUB, fontSize = 13.sp)
        }
    }
}

@Composable
private fun WeightsCard(vm: HardnessViewModel, onEdit: (Int) -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = SURF), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            vm.slots.forEach { slot ->
                val v = vm.weight(slot.no)
                val pending = vm.pendingSlot == slot.no
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                        .background(SURF2).outline(pending, if (pending) ACCENT else LINE)
                        .clickable { onEdit(slot.no) }
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier.size(30.dp).clip(RoundedCornerShape(9.dp)).background(BG),
                        contentAlignment = Alignment.Center
                    ) { Text("${slot.no}", color = ACCENT, fontFamily = MONO, fontWeight = FontWeight.Black, fontSize = 16.sp) }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(slot.name, color = TXT, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Text(slot.detail, color = SUB, fontSize = 12.sp)
                    }
                    Text(
                        v?.let { "${vm.fmt(it)}" } ?: "—",
                        color = if (pending) ACCENT else if (v == null) SUB else TXT,
                        fontFamily = MONO, fontSize = 24.sp, fontWeight = FontWeight.Black
                    )
                    Text(" g", color = SUB, fontSize = 14.sp)
                }
            }
        }
    }
}

@Composable
private fun VerifyCard(vm: HardnessViewModel) {
    val err = vm.massErrorPct
    val ok = vm.massOk
    val bg = when (ok) { true -> OKBG; false -> BADBG; null -> SURF }
    val ln = when (ok) { true -> OKLINE; false -> BADLINE; null -> LINE }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Card(
            colors = CardDefaults.cardColors(containerColor = bg),
            shape = RoundedCornerShape(18.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, ln)
        ) {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text("질량 검증", color = SUB, fontSize = 12.sp)
                    Text(
                        err?.let { "${vm.fmt(it)}%" } ?: "— %",
                        color = TXT, fontFamily = MONO, fontSize = 36.sp, fontWeight = FontWeight.Black
                    )
                    Text(
                        when (ok) { true -> "✓ 정상 · 오차 ≤2%"; false -> "✗ 재측정 · 오차 >2%"; null -> "값 3개 입력 필요" },
                        color = when (ok) { true -> ACCENT; false -> RED; null -> SUB },
                        fontSize = 12.sp, fontWeight = FontWeight.Bold
                    )
                }
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    CmpRow("가벼운 + 무거운", vm.sumLightHeavy?.let { "${vm.fmt(it)} g" } ?: "—")
                    Spacer(Modifier.height(4.dp))
                    CmpRow("초기 M₀", vm.weight(1)?.let { "${vm.fmt(it)} g" } ?: "—")
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically
        ) {
            Text("경도", color = SUB, fontSize = 14.sp)
            Text("계산식 확정 후 표시", color = TXT, fontSize = 14.sp)
        }
    }
}

@Composable
private fun CmpRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = SUB, fontSize = 13.sp)
        Text(value, color = TXT, fontFamily = MONO, fontSize = 15.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun VoiceBar(vm: HardnessViewModel, onTapTalk: () -> Unit) {
    Column(Modifier.padding(vertical = 10.dp)) {
        Card(colors = CardDefaults.cardColors(containerColor = SURF), shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.fillMaxWidth().padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(12.dp).clip(CircleShape).background(ACCENT))
                    Spacer(Modifier.width(9.dp))
                    Text("듣는 중 · ", color = SUB, fontSize = 14.sp)
                    Text(
                        if (vm.heardText.isBlank()) vm.status else "\"${vm.heardText}\"",
                        color = TXT, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1
                    )
                }
                Spacer(Modifier.height(11.dp))
                Surface(
                    onClick = onTapTalk,
                    color = SURF2, shape = RoundedCornerShape(14.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, LINE),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        "🎤  터치해서 말하기",
                        color = TXT, fontSize = 19.sp, fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center, modifier = Modifier.padding(vertical = 18.dp).fillMaxWidth()
                    )
                }
                if (vm.pendingSlot != null) {
                    val slot = vm.pendingSlot!!
                    Spacer(Modifier.height(11.dp))
                    Text("${slot}번 ${vm.slots[slot - 1].name} — 맞나요?", color = SUB, fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                    Text(
                        "${vm.weight(slot)?.let { vm.fmt(it) } ?: "—"} g",
                        color = ACCENT, fontFamily = MONO, fontSize = 26.sp, fontWeight = FontWeight.Black,
                        textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(11.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Button(
                            onClick = { vm.rejectPending() },
                            modifier = Modifier.weight(1f).height(64.dp),
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = SURF2)
                        ) { Text("✗ 아니", color = TXT, fontSize = 22.sp, fontWeight = FontWeight.Black) }
                        Button(
                            onClick = { vm.confirmPending() },
                            modifier = Modifier.weight(1f).height(64.dp),
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = ACCENT)
                        ) { Text("응 ✓", color = ACCENT_INK, fontSize = 22.sp, fontWeight = FontWeight.Black) }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "계량: \"사십오쩜삼사\" 또는 \"이번 구십사쩜육사\" · 타이머: \"시작/정지\" · 확인: \"응/아니\"",
                    color = SUB, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun DialEditor(
    slot: Int, slotName: String, initial: Double,
    onConfirm: (Double) -> Unit, onDismiss: () -> Unit
) {
    var value by remember { mutableStateOf(round2(initial)) }
    Dialog(onDismissRequest = onDismiss) {
        Card(colors = CardDefaults.cardColors(containerColor = SURF), shape = RoundedCornerShape(20.dp)) {
            Column(Modifier.padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("$slot 번 · $slotName", color = SUB, fontSize = 14.sp)
                Text("${fmt(value)} g", color = TXT, fontFamily = MONO, fontSize = 44.sp, fontWeight = FontWeight.Black)
                Spacer(Modifier.height(12.dp))
                StepperRow("1", 1.0, value) { value = clamp(it) }
                StepperRow("0.1", 0.1, value) { value = clamp(it) }
                StepperRow("0.01", 0.01, value) { value = clamp(it) }
                Spacer(Modifier.height(14.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = onDismiss, modifier = Modifier.weight(1f).height(56.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = SURF2)
                    ) { Text("취소", color = TXT, fontSize = 18.sp) }
                    Button(
                        onClick = { onConfirm(round2(value)) }, modifier = Modifier.weight(1f).height(56.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = ACCENT)
                    ) { Text("기록", color = ACCENT_INK, fontSize = 18.sp, fontWeight = FontWeight.Bold) }
                }
            }
        }
    }
}

@Composable
private fun StepperRow(label: String, delta: Double, value: Double, onChange: (Double) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        DialBtn("－") { onChange(value - delta) }
        Text("±$label", color = SUB, fontSize = 15.sp, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
        DialBtn("＋") { onChange(value + delta) }
    }
}

@Composable
private fun DialBtn(t: String, onClick: () -> Unit) {
    Button(
        onClick = onClick, modifier = Modifier.size(60.dp), shape = CircleShape,
        contentPadding = PaddingValues(0.dp),
        colors = ButtonDefaults.buttonColors(containerColor = SURF2)
    ) { Text(t, color = TXT, fontSize = 24.sp, fontWeight = FontWeight.Bold) }
}

@Composable
private fun BigButton(text: String, bg: Color, fg: Color, onClick: () -> Unit) {
    Button(
        onClick = onClick, modifier = Modifier.fillMaxWidth().height(76.dp),
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(containerColor = bg)
    ) { Text(text, color = fg, fontSize = 26.sp, fontWeight = FontWeight.Black) }
}

private fun clamp(v: Double) = max(0.0, min(200.0, round2(v)))
private fun round2(v: Double) = Math.round(v * 100.0) / 100.0
private fun fmt(v: Double) = String.format(java.util.Locale.US, "%.2f", v)
