package com.carbon.hardness

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

class HardnessViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = Prefs(app)
    val history = History(app)
    private val ctx get() = getApplication<Application>()

    /** Activity 가 주입: 음성엔진 켜기/끄기 요청 */
    var requestMic: (Boolean) -> Unit = {}

    val stages = Experiment.stages
    val slots = Experiment.slots

    var currentStage by mutableStateOf(0); private set

    private val wState = mutableStateListOf<Double?>(null, null, null)
    fun weight(slot: Int): Double? = wState[slot - 1]

    var pendingSlot by mutableStateOf<Int?>(null); private set
    private var prevValue: Double? = null

    data class TimerInfo(val status: Int, val start: Long, val end: Long) // 0 idle,1 run,2 done
    val timers = mutableStateMapOf<Int, TimerInfo>()

    var nowMillis by mutableStateOf(System.currentTimeMillis()); private set
    var heardText by mutableStateOf(""); private set
    var status by mutableStateOf("마이크를 켜고 \"거르기 시작\"이라고 말해보세요"); private set

    // ---- 마이크 상태 ----
    var micOn by mutableStateOf(false); private set          // 엔진 콜백으로 갱신
    var voiceMode by mutableStateOf(true); private set       // false = 수동 모드
    var onDeviceStt by mutableStateOf(false)                 // 어떤 인식 엔진인지 (표시용)
    private var lastVoiceAt = 0L

    // ---- 시료/이력 ----
    var sampleName by mutableStateOf(""); private set
    var confirmingReset by mutableStateOf(false); private set
    var savedRun by mutableStateOf(false); private set

    // ---- 설정 (기본 무게 · 타이머 분) ----
    private val defWeights = mutableStateMapOf<Int, Double>()
    private val stageMins = mutableStateMapOf<Int, Int>()

    fun defaultWeight(slot: Int): Double = defWeights[slot]
        ?: prefs.defaultWeight(slot, if (slot == 3) 1.50 else 45.00)

    fun setDefaultWeight(slot: Int, v: Double) {
        defWeights[slot] = v
        prefs.setDefaultWeight(slot, v)
    }

    fun stageMinutes(stage: Int): Int = stageMins[stage]
        ?: prefs.stageMinutes(stage, stages[stage].minutes)

    fun setStageMinutes(stage: Int, min: Int) {
        if (min < 1) return
        stageMins[stage] = min
        prefs.setStageMinutes(stage, min)
    }

    // ---- 업데이트 ----
    var updateInfo by mutableStateOf<Updater.Release?>(null); private set
    var updateBusy by mutableStateOf(false); private set
    var updateMsg by mutableStateOf(""); private set

    fun checkUpdate(silent: Boolean) {
        if (updateBusy) return
        updateBusy = true
        if (!silent) updateMsg = "확인 중…"
        viewModelScope.launch {
            val rel = withContext(Dispatchers.IO) { Updater.fetchLatest() }
            prefs.lastUpdateCheck = System.currentTimeMillis()
            val cur = Updater.currentVersion(ctx)
            if (rel != null && Updater.isNewer(rel.tag, cur) && rel.apkUrl != null) {
                updateInfo = rel
                updateMsg = "새 버전 ${rel.tag} 있음"
            } else {
                updateInfo = null
                if (!silent) updateMsg = if (rel == null) "확인 실패 (인터넷 확인)" else "최신 버전입니다 ✓"
            }
            updateBusy = false
        }
    }

    fun downloadAndInstall() {
        val rel = updateInfo ?: return
        val url = rel.apkUrl ?: return
        if (updateBusy) return
        updateBusy = true
        updateMsg = "다운로드 중… (수 초 걸려요)"
        viewModelScope.launch {
            val file = withContext(Dispatchers.IO) { Updater.downloadApk(ctx, url) }
            updateBusy = false
            if (file != null) {
                updateMsg = "설치 화면으로 이동합니다"
                Updater.install(ctx, file)
            } else {
                updateMsg = "다운로드 실패 · 다시 시도해주세요"
            }
        }
    }

    init {
        restore()
        // 하루 1회 자동 업데이트 확인
        if (System.currentTimeMillis() - prefs.lastUpdateCheck > 24 * 3600_000L) {
            checkUpdate(silent = true)
        }
        viewModelScope.launch {
            while (true) {
                nowMillis = System.currentTimeMillis()
                reconcileTimers()
                // 5분간 인식 없으면 마이크 자동 꺼짐 (배터리 보호)
                if (micOn && lastVoiceAt > 0 && nowMillis - lastVoiceAt > 5 * 60_000L) {
                    requestMic(false)
                    status = "5분간 조용해서 마이크를 껐어요"
                }
                delay(1000)
            }
        }
    }

    private fun restore() {
        currentStage = prefs.currentStage
        wState[0] = prefs.w1; wState[1] = prefs.w2; wState[2] = prefs.w3
        voiceMode = prefs.voiceMode
        sampleName = prefs.sampleName
        savedRun = prefs.savedRun
        for (s in Experiment.timerStageIndices) {
            timers[s] = TimerInfo(prefs.timerStatus(s), prefs.timerStart(s), prefs.timerEnd(s))
        }
    }

    private fun reconcileTimers() {
        for (s in Experiment.timerStageIndices) {
            val t = timers[s] ?: continue
            if (t.status == 1 && nowMillis >= t.end) {
                timers[s] = t.copy(status = 2)
                prefs.setTimer(s, 2, t.start, t.end)
                Notifications.cancelCountdown(ctx)
                if (currentStage == s && s < stages.size - 1) {
                    currentStage = s + 1; prefs.currentStage = currentStage
                }
                status = "${stages[s].short} 완료 · ${guideFor(currentStage)}"
            }
        }
    }

    // ---------- 마이크 ----------
    fun onMicStateChanged(on: Boolean) {
        micOn = on
        if (on) lastVoiceAt = System.currentTimeMillis()
    }

    /** 음성엔진에서 온 안내 메시지 (엔진 전환·오류 등) */
    fun showNotice(text: String) { status = text }

    fun toggleMic() {
        if (!voiceMode) return
        requestMic(!micOn)
    }

    fun updateVoiceMode(on: Boolean) {
        voiceMode = on
        prefs.voiceMode = on
        if (!on) requestMic(false)
        status = if (on) "음성 모드 · 마이크를 켜서 사용하세요" else "수동 모드 · 화면 터치로 조작"
    }

    // ---------- 음성 진입점 ----------
    fun onPartial(text: String) { heardText = text; lastVoiceAt = System.currentTimeMillis() }

    fun onFinal(text: String) {
        heardText = text
        lastVoiceAt = System.currentTimeMillis()
        val p = VoiceParser.parse(text)

        // 시료명
        if (p.sampleName != null) {
            updateSampleName(p.sampleName)
            return
        }

        // 초기화 확인 대기 중이면 응/아니만 받는다
        if (confirmingReset) {
            when (p.command) {
                Command.CONFIRM -> executeReset()
                Command.REJECT -> { confirmingReset = false; status = "초기화 취소" }
                else -> Unit
            }
            return
        }

        // 명령 우선
        if (p.command != null && p.number == null) {
            when (p.command) {
                Command.CONFIRM -> if (pendingSlot != null) confirmPending()
                Command.REJECT -> if (pendingSlot != null) rejectPending()
                Command.START_TIMER -> if (p.stageRef != null) startTimer(p.stageRef) else startCurrentTimer()
                Command.STOP_TIMER -> activeTimerStage?.let { stopTimer(it, byUser = true) }
                Command.NEXT -> next()
                Command.PREV -> prev()
                Command.RESET -> requestReset()
                Command.SAVE -> Unit
                else -> Unit
            }
            return
        }

        // 숫자
        if (p.number != null) {
            val slot = p.slot ?: focusedSlotOrNull()
            if (slot != null) setWeight(slot, p.number)
            else status = "지금은 계량 단계가 아니에요 · \"이번 구십사쩜육사\"처럼 번호를 붙이면 기록됩니다"
        }
    }

    private fun focusedSlotOrNull(): Int? {
        val st = stages[currentStage]
        if (st.kind != StageKind.WEIGH) return null
        return st.weighSlots.firstOrNull { wState[it - 1] == null } ?: st.weighSlots.last()
    }

    /** 다음에 할 일을 안내하는 문장 */
    private fun guideFor(i: Int): String {
        val s = stages[i]
        return if (s.kind == StageKind.TIMER) {
            "${s.title} — \"${s.short} 시작\"이라고 말하세요"
        } else {
            val n = s.weighSlots.firstOrNull { wState[it - 1] == null } ?: s.weighSlots.first()
            "${n}번 ${slotName(n)} 무게를 말하세요"
        }
    }

    // ---------- 무게 ----------
    /** confirm=true(음성): "맞나요?" 확인 후 확정. false(수동 입력): 즉시 확정 */
    fun setWeight(slot: Int, value: Double, confirm: Boolean = true) {
        if (slot !in 1..3) return
        // 직전 측정이 이미 저장된 상태에서 초기무게(1번)를 새로 넣으면 = 새 시료 시작.
        // 이전 시료의 무거운/가벼운이 남아 엉터리 경도가 저장되는 것 방지.
        if (slot == 1 && savedRun) {
            wState[1] = null
            wState[2] = null
            prefs.sampleStart = 0L
        }
        prevValue = wState[slot - 1]
        wState[slot - 1] = value
        persistWeights()
        if (confirm) {
            pendingSlot = slot
            status = "${slotName(slot)} = ${fmt(value)} g · 맞나요?"
        } else {
            pendingSlot = null
            advanceAfterWeight(slot)
            maybeSave()
        }
    }

    fun confirmPending() {
        val confirmed = pendingSlot ?: return
        pendingSlot = null
        advanceAfterWeight(confirmed)
        maybeSave()
    }

    private fun advanceAfterWeight(confirmed: Int) {
        val st = stages[currentStage]
        if (st.kind == StageKind.WEIGH) {
            val remaining = st.weighSlots.filter { wState[it - 1] == null }
            if (remaining.isEmpty() && currentStage < stages.size - 1) {
                next()
                status = "확정 · ${guideFor(currentStage)}"
            } else if (remaining.isNotEmpty()) {
                status = "${slotName(confirmed)} 확정 · 이어서 ${guideFor(currentStage)}"
            } else {
                status = "확정"
            }
        } else {
            status = "${slotName(confirmed)} = ${wState[confirmed - 1]?.let { fmt(it) }} g 확정"
        }
    }

    /** 세 무게가 모두 확정되면 (단계 무관) 즉시 이력에 저장 */
    private fun maybeSave() {
        if (pendingSlot != null || savedRun) return
        val w1 = wState[0] ?: return
        val w2 = wState[1] ?: return
        val w3 = wState[2] ?: return
        val h = hardness ?: return
        history.add(
            HistoryRecord(System.currentTimeMillis(), sampleName, w1, w2, w3, h, massErrorPct ?: 0.0)
        )
        savedRun = true
        prefs.savedRun = true
        status = when (massOk) {
            true -> "경도 ${fmt(h)}% · 이력에 저장됨 ✓"
            false -> "경도 ${fmt(h)}% · 오차 초과, 재측정 권장 · 저장됨"
            null -> "이력에 저장됨 ✓"
        }
    }

    fun rejectPending() {
        pendingSlot?.let {
            wState[it - 1] = prevValue
            persistWeights()
        }
        pendingSlot = null
        status = "취소됨 · 다시 말하세요"
    }

    fun editSlot(slot: Int) { pendingSlot = slot }

    private fun persistWeights() {
        prefs.w1 = wState[0]; prefs.w2 = wState[1]; prefs.w3 = wState[2]
        prefs.savedRun = false
        savedRun = false
    }

    private fun slotName(slot: Int) = slots[slot - 1].name

    // ---------- 시료명 ----------
    fun updateSampleName(name: String) {
        sampleName = name.trim()
        prefs.sampleName = sampleName
        status = "시료명: $sampleName"
    }

    // ---------- 타이머 ----------
    val activeTimerStage: Int?
        get() = Experiment.timerStageIndices.firstOrNull { (timers[it]?.status ?: 0) == 1 }

    fun timerStatus(stage: Int) = timers[stage]?.status ?: 0
    fun timerEnd(stage: Int) = timers[stage]?.end ?: 0L
    fun timerStart(stage: Int) = timers[stage]?.start ?: 0L

    fun elapsedMin(stage: Int): Int {
        val s = timerStart(stage)
        return if (s == 0L) 0 else ((nowMillis - s) / 60_000L).toInt().coerceAtLeast(0)
    }

    /** 이번 회차만 적용되는 임시 타이머 시간 */
    private val oneOffMin = mutableStateMapOf<Int, Int>()

    /** 이번 회차에 적용될 분 (일회성 우선) */
    fun effectiveMinutes(stage: Int): Int = oneOffMin[stage] ?: stageMinutes(stage)

    fun remainingSec(stage: Int): Int {
        val t = timers[stage] ?: return effectiveMinutes(stage) * 60
        return if (t.status == 1) ((t.end - nowMillis) / 1000).toInt().coerceAtLeast(0)
        else effectiveMinutes(stage) * 60
    }

    /**
     * 타이머 시간 일회성 변경.
     * 진행 중이면 남은 시간을 바로 재설정(알람 재예약), 대기 중이면 이번 시작에만 적용.
     */
    fun editTimer(stage: Int, min: Int) {
        if (min < 1 || stages[stage].kind != StageKind.TIMER) return
        val t = timers[stage]
        if (t?.status == 1) {
            val end = System.currentTimeMillis() + min * 60_000L
            timers[stage] = t.copy(end = end)
            prefs.setTimer(stage, 1, t.start, end)
            AlarmScheduler.schedule(ctx, end, stage, "${stages[stage].short} ${min}분")
            Notifications.showCountdown(ctx, "${stages[stage].short} ${min}분", end)
            status = "남은 시간 ${min}분으로 변경 · 완료 ${TimeFmt.clock(end)}"
        } else {
            oneOffMin[stage] = min
            status = "이번 ${stages[stage].short}만 ${min}분으로 · \"시작\"하면 적용"
        }
    }

    /** 타이머를 안 돌렸어도 실제로 작업을 끝냈으면 수동 완료 처리 */
    fun completeTimerStage(stage: Int) {
        if (stages[stage].kind != StageKind.TIMER) return
        if (timers[stage]?.status == 1) {
            AlarmScheduler.cancel(ctx)
            Notifications.cancelCountdown(ctx)
        }
        val now = System.currentTimeMillis()
        val start = timers[stage]?.start?.takeIf { it != 0L } ?: now
        timers[stage] = TimerInfo(2, start, now)
        prefs.setTimer(stage, 2, start, now)
        oneOffMin.remove(stage)
        if (currentStage == stage && stage < stages.size - 1) {
            currentStage = stage + 1; prefs.currentStage = currentStage
        }
        status = "${stages[stage].short} 완료 처리 · ${guideFor(currentStage)}"
    }

    fun startCurrentTimer() {
        val st = currentStage
        if (stages[st].kind != StageKind.TIMER) {
            status = "이 단계는 타이머가 없어요 · \"거르기 시작\"처럼 단계명을 붙여보세요"
            return
        }
        startTimer(st)
    }

    fun startTimer(stage: Int) {
        if (stages[stage].kind != StageKind.TIMER) return
        activeTimerStage?.let { if (it != stage) stopTimer(it, byUser = false) }
        val min = effectiveMinutes(stage)
        oneOffMin.remove(stage)   // 일회성이므로 사용 후 제거
        val start = System.currentTimeMillis()
        val end = start + min * 60_000L
        timers[stage] = TimerInfo(1, start, end)
        prefs.setTimer(stage, 1, start, end)
        if (prefs.sampleStart == 0L) prefs.sampleStart = start
        if (currentStage != stage) { currentStage = stage; prefs.currentStage = stage }
        AlarmScheduler.schedule(ctx, end, stage, "${stages[stage].short} ${min}분")
        Notifications.showCountdown(ctx, "${stages[stage].short} ${min}분", end)
        status = "${stages[stage].short} 시작 · 완료 ${TimeFmt.clock(end)}"
        // 타이머가 돌기 시작하면 말할 일 없음 → 마이크 자동 꺼짐
        requestMic(false)
    }

    fun stopTimer(stage: Int, byUser: Boolean) {
        AlarmScheduler.cancel(ctx)
        Notifications.cancelCountdown(ctx)
        val t = timers[stage] ?: TimerInfo(0, 0, 0)
        timers[stage] = TimerInfo(0, t.start, 0)
        prefs.setTimer(stage, 0, t.start, 0)
        if (byUser) status = "${stages[stage].short} 타이머 정지"
    }

    // ---------- 네비게이션 ----------
    fun next() { if (currentStage < stages.size - 1) { currentStage++; prefs.currentStage = currentStage } }
    fun prev() { if (currentStage > 0) { currentStage--; prefs.currentStage = currentStage } }
    fun goStage(i: Int) { if (i in stages.indices) { currentStage = i; prefs.currentStage = i } }

    // ---------- 계산 ----------
    val hardness: Double?
        get() {
            val m0 = wState[0]; val heavy = wState[1]
            return if (m0 != null && heavy != null && m0 > 0) heavy / m0 * 100.0 else null
        }

    val sumLightHeavy: Double?
        get() { val a = wState[1]; val b = wState[2]; return if (a != null && b != null) a + b else null }

    val massErrorPct: Double?
        get() {
            val s = sumLightHeavy; val m0 = wState[0]
            return if (s != null && m0 != null && m0 > 0) abs(s - m0) / m0 * 100.0 else null
        }
    val massOk: Boolean? get() = massErrorPct?.let { it <= 2.0 }

    // ---------- 초기화 (확인 필수) ----------
    fun requestReset() {
        confirmingReset = true
        status = "모든 값을 지울까요? \"응\" 또는 버튼으로 확인"
    }

    fun cancelReset() { confirmingReset = false; status = "초기화 취소" }

    fun executeReset() {
        confirmingReset = false
        activeTimerStage?.let { stopTimer(it, byUser = false) }
        AlarmScheduler.cancel(ctx)
        Notifications.cancelCountdown(ctx)
        wState[0] = null; wState[1] = null; wState[2] = null
        pendingSlot = null; prevValue = null
        currentStage = 0
        timers.clear()
        prefs.clearRun()   // 설정(기본무게·타이머 분·모드)은 유지
        sampleName = ""
        savedRun = false
        status = "새 측정 시작 · 시료명을 정하고 \"거르기 시작\""
    }

    fun fmt(v: Double) = String.format(java.util.Locale.US, "%.2f", v)
}
