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
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
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

    private var backupData: String = ""
    private val backupLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            try {
                contentResolver.openOutputStream(uri)?.use { it.write(backupData.toByteArray()) }
                toast("백업 저장 완료")
            } catch (_: Exception) { toast("저장 실패") }
        }
    }
    private val restoreLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            try {
                val text = contentResolver.openInputStream(uri)?.bufferedReader()?.readText() ?: ""
                val added = vm.history.importJson(text)
                toast(if (added >= 0) "${added}건 불러옴" else "파일 형식이 아니에요")
            } catch (_: Exception) { toast("불러오기 실패") }
        }
    }

    private fun toast(msg: String) =
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()

    fun startBackup() {
        backupData = vm.history.exportJson()
        backupLauncher.launch("활성탄경도_백업_${SimpleDateFormat("yyMMdd", Locale.KOREA).format(Date())}.json")
    }

    fun startRestore() = restoreLauncher.launch(arrayOf("application/json", "text/*", "*/*"))

    fun shareCsv() {
        try {
            val f = java.io.File(cacheDir, "export/hardness_history.csv")
            f.parentFile?.mkdirs()
            f.writeText(History.toCsv(vm.history.all()))
            val uri = androidx.core.content.FileProvider.getUriForFile(
                this, "com.carbon.hardness.fileprovider", f
            )
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send, "CSV 파일 보내기"))
        } catch (_: Exception) { toast("CSV 생성 실패") }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        Notifications.ensureChannels(this)

        voice = VoiceEngine(
            context = this,
            onPartial = { vm.onPartial(it) },
            onFinal = { vm.onFinal(it) },
            onStateChange = {
                vm.onMicStateChanged(it)
                vm.onDeviceStt = voice?.usingOnDevice == true
            },
            onNotice = {
                vm.showNotice(it)
                vm.onDeviceStt = voice?.usingOnDevice == true
            },
        ).also { it.init() }
        vm.onDeviceStt = voice?.usingOnDevice == true

        vm.requestMic = { on ->
            if (on) {
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
                    voice?.start()
            } else voice?.stop()
        }

        requestPerms()
        setContent {
            AppScreen(
                vm,
                onShare = { shareText(it) },
                onShareCsv = { shareCsv() },
                onBackup = { startBackup() },
                onRestore = { startRestore() },
            )
        }
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

    override fun onPause() { super.onPause(); voice?.stop() }
    override fun onDestroy() { voice?.destroy(); super.onDestroy() }
}

@Composable
private fun AppScreen(
    vm: HardnessViewModel,
    onShare: (String) -> Unit,
    onShareCsv: () -> Unit,
    onBackup: () -> Unit,
    onRestore: () -> Unit,
) {
    var editSlot by remember { mutableStateOf(0) }
    var editName by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().background(BG).padding(horizontal = 14.dp)) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(top = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Header(vm, onShare, onShareCsv, onEditName = { editName = true },
                onHistory = { showHistory = true }, onSettings = { showSettings = true })
            StepStrip(vm)
            GuideBanner(vm)
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
            initial = vm.weight(editSlot) ?: vm.defaultWeight(editSlot),
            onConfirm = { vm.setWeight(editSlot, it, confirm = false); editSlot = 0 },
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
    if (showSettings) {
        SettingsScreen(vm, onShareCsv, onBackup, onRestore, onDismiss = { showSettings = false })
    }
}

// ---------- 헤더 ----------
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Header(
    vm: HardnessViewModel, onShare: (String) -> Unit, onShareCsv: () -> Unit,
    onEditName: () -> Unit, onHistory: () -> Unit, onSettings: () -> Unit
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
            ToolChip(
                text = if (vm.updateInfo != null) "설정 🔴" else "설정",
                active = false, onClick = onSettings
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
                    DropdownMenuItem(text = { Text("CSV 파일로 보내기") },
                        onClick = { shareMenu = false; onShareCsv() })
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

// ---------- 다음 할 일 안내 배너 ----------
@Composable
private fun GuideBanner(vm: HardnessViewModel) {
    Card(
        colors = CardDefaults.cardColors(containerColor = ACCENT),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("💬", fontSize = 20.sp)
            Spacer(Modifier.width(12.dp))
            Column {
                Text("다음 할 일", color = Color(0xFFC9DAFB), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Text(
                    vm.status,
                    color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Black,
                    lineHeight = 21.sp
                )
            }
        }
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
                        else -> Text("${vm.effectiveMinutes(s.index)}′", color = SUB, fontSize = 10.sp, maxLines = 1)
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
    var editingTime by remember { mutableStateOf(false) }
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
                    else -> Triple("${vm.effectiveMinutes(stage)}분", BG, SUB)
                }
                Box(Modifier.clip(RoundedCornerShape(999.dp)).background(tagBg).padding(horizontal = 10.dp, vertical = 4.dp)) {
                    Text(tag, color = tagFg, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
            val remain = if (st == 1) vm.remainingSec(stage) else vm.effectiveMinutes(stage) * 60
            Text(
                TimeFmt.mmss(remain),
                color = if (st == 1) ACCENT else TXT, fontFamily = MONO,
                fontSize = 64.sp, fontWeight = FontWeight.Black,
                modifier = Modifier.clickable { if (st != 2) editingTime = true }
            )
            if (st == 1) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("시작 ${TimeFmt.clock(vm.timerStart(stage))}", color = SUB, fontSize = 15.sp)
                    Text("→", color = SUB, fontSize = 15.sp)
                    Text("완료 ${TimeFmt.clock(vm.timerEnd(stage))}", color = ACCENT, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                }
            } else {
                Text("숫자를 누르면 이번만 시간 변경", color = SUB, fontSize = 13.sp)
            }
            Spacer(Modifier.height(14.dp))
            when (st) {
                1 -> BigButton("정지", RED, Color.White) { vm.stopTimer(stage, byUser = true) }
                2 -> BigButton("다시 시작", BG, TXT) { vm.startTimer(stage) }
                else -> BigButton("시작", ACCENT, Color.White) { vm.startTimer(stage) }
            }
            if (st != 2) {
                Spacer(Modifier.height(8.dp))
                Box(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                        .background(BG).clickable { vm.completeTimerStage(stage) }
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "이미 끝냈어요 — 완료 처리 ✓",
                        color = SUB, fontSize = 15.sp, fontWeight = FontWeight.Bold
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Text("음성: \"${s.short} 시작\" · \"정지\"", color = SUB, fontSize = 13.sp)
        }
    }

    if (editingTime) {
        TimerEditDialog(
            title = s.title,
            initialMin = if (st == 1) ((vm.remainingSec(stage) + 59) / 60).coerceAtLeast(1)
            else vm.effectiveMinutes(stage),
            running = st == 1,
            onApply = { vm.editTimer(stage, it); editingTime = false },
            onDismiss = { editingTime = false }
        )
    }
}

@Composable
private fun TimerEditDialog(
    title: String, initialMin: Int, running: Boolean,
    onApply: (Int) -> Unit, onDismiss: () -> Unit
) {
    var text by remember { mutableStateOf(initialMin.toString()) }
    Dialog(onDismissRequest = onDismiss) {
        Card(colors = CardDefaults.cardColors(containerColor = SURF), shape = RoundedCornerShape(22.dp)) {
            Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(title, color = SUB, fontSize = 14.sp)
                Text(
                    if (running) "남은 시간 변경 (분)" else "이번만 타이머 시간 변경 (분)",
                    color = TXT, fontSize = 17.sp, fontWeight = FontWeight.Black
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = text, onValueChange = { text = it }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    textStyle = androidx.compose.ui.text.TextStyle(
                        fontFamily = MONO, fontSize = 34.sp, fontWeight = FontWeight.Black,
                        textAlign = TextAlign.Center
                    ),
                    modifier = Modifier.width(140.dp)
                )
                Spacer(Modifier.height(8.dp))
                Text("기본 시간은 설정에서 바꿔요 · 이건 이번 한 번만", color = SUB, fontSize = 11.sp)
                Spacer(Modifier.height(14.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = onDismiss, modifier = Modifier.weight(1f).height(52.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = BG)
                    ) { Text("취소", color = TXT, fontSize = 16.sp) }
                    Button(
                        onClick = { text.toIntOrNull()?.let { if (it >= 1) onApply(it) } },
                        modifier = Modifier.weight(1f).height(52.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = ACCENT)
                    ) { Text("적용", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold) }
                }
            }
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
                    "✓ 이력에 저장됨 — 지금 값을 고치면 이 기록이 자동 수정돼요",
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
                                if (vm.heardText.isBlank()) "말씀하세요…" else "\"${vm.heardText}\"",
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
                            Text(
                                (if (vm.onDeviceStt) "온디바이스 인식" else "일반 인식") +
                                    " · 여기를 눌러 켜기",
                                color = SUB, fontSize = 13.sp, maxLines = 1
                            )
                        }
                    }
                }
            }
        }
    }
}

// ---------- 이력 화면 ----------
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HistoryScreen(vm: HardnessViewModel, onShare: (String) -> Unit, onDismiss: () -> Unit) {
    var records by remember { mutableStateOf(vm.history.all()) }
    var deleting by remember { mutableStateOf<HistoryRecord?>(null) }
    var editing by remember { mutableStateOf<HistoryRecord?>(null) }
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
                Text("탭하면 수정 · 길게 누르면 삭제", color = SUB, fontSize = 11.sp)
                Spacer(Modifier.height(6.dp))
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
                                    .background(BG)
                                    .combinedClickable(onClick = { editing = r }, onLongClick = { deleting = r })
                                    .padding(12.dp)
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

    editing?.let { r ->
        EditRecordDialog(
            record = r,
            onSave = { name, w1, w2, w3 ->
                vm.history.update(r.ts, name, w1, w2, w3)
                records = vm.history.all()
                editing = null
            },
            onDismiss = { editing = null }
        )
    }

    deleting?.let { r ->
        Dialog(onDismissRequest = { deleting = null }) {
            Card(colors = CardDefaults.cardColors(containerColor = SURF), shape = RoundedCornerShape(22.dp)) {
                Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("이 기록을 삭제할까요?", color = TXT, fontSize = 17.sp, fontWeight = FontWeight.Black)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "${r.name.ifBlank { "(이름 없음)" }} · 경도 ${f2(r.hardness)}%",
                        color = SUB, fontSize = 14.sp
                    )
                    Spacer(Modifier.height(14.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(
                            onClick = { deleting = null }, modifier = Modifier.weight(1f).height(52.dp),
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = BG)
                        ) { Text("취소", color = TXT, fontSize = 16.sp) }
                        Button(
                            onClick = {
                                vm.history.remove(r.ts)
                                records = vm.history.all()
                                deleting = null
                            },
                            modifier = Modifier.weight(1f).height(52.dp),
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = RED)
                        ) { Text("삭제", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold) }
                    }
                }
            }
        }
    }
}

// ---------- 이력 수정 ----------
@Composable
private fun EditRecordDialog(
    record: HistoryRecord,
    onSave: (String, Double, Double, Double) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(record.name) }
    var w1 by remember { mutableStateOf(f2(record.w1)) }
    var w2 by remember { mutableStateOf(f2(record.w2)) }
    var w3 by remember { mutableStateOf(f2(record.w3)) }

    val pw1 = w1.toDoubleOrNull(); val pw2 = w2.toDoubleOrNull(); val pw3 = w3.toDoubleOrNull()
    val valid = pw1 != null && pw1 > 0 && pw2 != null && pw3 != null
    val prevH = if (valid) pw2!! / pw1!! * 100.0 else null
    val prevE = if (valid) kotlin.math.abs(pw2!! + pw3!! - pw1!!) / pw1!! * 100.0 else null

    Dialog(onDismissRequest = onDismiss) {
        Card(colors = CardDefaults.cardColors(containerColor = SURF), shape = RoundedCornerShape(22.dp)) {
            Column(Modifier.padding(20.dp)) {
                Text("기록 수정", color = TXT, fontSize = 17.sp, fontWeight = FontWeight.Black)
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = name, onValueChange = { name = it }, singleLine = true,
                    label = { Text("시료 이름") }, modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(6.dp))
                NumField("초기 M₀ (g)", w1) { w1 = it }
                NumField("무거운 (g)", w2) { w2 = it }
                NumField("가벼운 (g)", w3) { w3 = it }
                Spacer(Modifier.height(8.dp))
                Text(
                    if (prevH != null) "→ 경도 ${f2(prevH)}% · 질량오차 ${f2(prevE!!)}% (자동 재계산)"
                    else "숫자를 확인해주세요",
                    color = if (prevH != null) GREEN else RED,
                    fontSize = 13.sp, fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(14.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = onDismiss, modifier = Modifier.weight(1f).height(52.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = BG)
                    ) { Text("취소", color = TXT, fontSize = 16.sp) }
                    Button(
                        onClick = { if (valid) onSave(name, pw1!!, pw2!!, pw3!!) },
                        enabled = valid,
                        modifier = Modifier.weight(1f).height(52.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = ACCENT)
                    ) { Text("저장", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold) }
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

/**
 * 눈금이 손가락을 따라 움직이는 가로 다이얼.
 * - 손이 화면에 붙어 있는 동안: 항상 0.01 단위 정밀 조작
 * - 튕기듯 놓으면(fling): 관성으로 0.1 단위로 쫘라락 감속
 */
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
            .draggable(
                orientation = Orientation.Horizontal,
                state = rememberDraggableState { delta ->
                    // 터치 중엔 속도와 무관하게 0.01 정밀
                    curOnChange(curValue - delta / tickSpacingPx * 0.01)
                },
                onDragStopped = { velocity ->
                    // 놓는 순간 빠르면 관성 회전 (0.1 단위)
                    var v = velocity
                    if (kotlin.math.abs(v) > 900f) {
                        var cur = curValue
                        while (kotlin.math.abs(v) > 70f) {
                            cur -= (v * 0.016f) / tickSpacingPx * 0.1
                            cur = cur.coerceIn(0.0, 200.0)
                            curOnChange(Math.round(cur * 10.0) / 10.0)
                            v *= 0.93f
                            kotlinx.coroutines.delay(16)
                        }
                    }
                }
            )
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

// ---------- 설정 ----------
@Composable
private fun SettingsScreen(
    vm: HardnessViewModel,
    onShareCsv: () -> Unit, onBackup: () -> Unit, onRestore: () -> Unit,
    onDismiss: () -> Unit
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val version = remember { Updater.currentVersion(ctx) }
    var dw1 by remember { mutableStateOf(fmt(vm.defaultWeight(1))) }
    var dw2 by remember { mutableStateOf(fmt(vm.defaultWeight(2))) }
    var dw3 by remember { mutableStateOf(fmt(vm.defaultWeight(3))) }
    var m0 by remember { mutableStateOf(vm.stageMinutes(0).toString()) }
    var m2 by remember { mutableStateOf(vm.stageMinutes(2).toString()) }
    var m3 by remember { mutableStateOf(vm.stageMinutes(3).toString()) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            colors = CardDefaults.cardColors(containerColor = SURF),
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier.fillMaxWidth(0.94f).fillMaxHeight(0.9f)
        ) {
            Column(Modifier.fillMaxSize().padding(18.dp).verticalScroll(rememberScrollState())) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("설정", color = TXT, fontSize = 19.sp, fontWeight = FontWeight.Black)
                    Spacer(Modifier.weight(1f))
                    ToolChip(text = "닫기", active = false, onClick = onDismiss)
                }

                // 앱 정보 / 업데이트
                SectionTitle("앱 정보")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("현재 버전 v$version", color = TXT, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        if (vm.updateMsg.isNotBlank())
                            Text(vm.updateMsg, color = if (vm.updateInfo != null) ACCENT else SUB, fontSize = 13.sp)
                    }
                    if (vm.updateInfo != null) {
                        Button(
                            onClick = { vm.downloadAndInstall() },
                            enabled = !vm.updateBusy,
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = ACCENT)
                        ) { Text("업데이트 설치", color = Color.White, fontWeight = FontWeight.Bold) }
                    } else {
                        Button(
                            onClick = { vm.checkUpdate(silent = false) },
                            enabled = !vm.updateBusy,
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = ACCENT_SOFT)
                        ) { Text("업데이트 확인", color = ACCENT, fontWeight = FontWeight.Bold) }
                    }
                }

                // 기본 무게
                SectionTitle("다이얼 기본 무게 (g)")
                Text("무게칸을 열었을 때 시작하는 값이에요", color = SUB, fontSize = 12.sp)
                Spacer(Modifier.height(8.dp))
                NumField("1번 초기 M₀", dw1) { dw1 = it }
                NumField("2번 무거운", dw2) { dw2 = it }
                NumField("3번 가벼운", dw3) { dw3 = it }

                // 타이머
                SectionTitle("타이머 시간 (분)")
                NumField("거르기", m0) { m0 = it }
                NumField("쇠구슬 (마모)", m2) { m2 = it }
                NumField("체진동기", m3) { m3 = it }

                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = {
                        dw1.toDoubleOrNull()?.let { vm.setDefaultWeight(1, round2(it)) }
                        dw2.toDoubleOrNull()?.let { vm.setDefaultWeight(2, round2(it)) }
                        dw3.toDoubleOrNull()?.let { vm.setDefaultWeight(3, round2(it)) }
                        m0.toIntOrNull()?.let { vm.setStageMinutes(0, it) }
                        m2.toIntOrNull()?.let { vm.setStageMinutes(2, it) }
                        m3.toIntOrNull()?.let { vm.setStageMinutes(3, it) }
                        onDismiss()
                    },
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = ACCENT)
                ) { Text("저장", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold) }

                // 데이터
                SectionTitle("데이터")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ToolChip(text = "CSV 파일 📤", active = false, onClick = onShareCsv)
                    ToolChip(text = "백업 저장", active = false, onClick = onBackup)
                    ToolChip(text = "백업 불러오기", active = false, onClick = onRestore)
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "CSV: 엑셀에서 바로 열리는 파일 · 백업: 이력 전체를 파일로 저장/복원 (폰 교체 대비)",
                    color = SUB, fontSize = 12.sp
                )
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun SectionTitle(t: String) {
    Spacer(Modifier.height(18.dp))
    Text(t, color = ACCENT, fontSize = 13.sp, fontWeight = FontWeight.Black)
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun NumField(label: String, value: String, onChange: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = TXT, fontSize = 14.sp, modifier = Modifier.weight(1f))
        OutlinedTextField(
            value = value, onValueChange = onChange, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            textStyle = androidx.compose.ui.text.TextStyle(
                fontFamily = MONO, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            ),
            modifier = Modifier.width(120.dp)
        )
    }
}

private fun clampD(v: Double) = max(0.0, min(200.0, v))
private fun round2(v: Double) = Math.round(v * 100.0) / 100.0
private fun fmt(v: Double) = String.format(Locale.US, "%.2f", v)
private fun f2(v: Double) = String.format(Locale.US, "%.2f", v)
