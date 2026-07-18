package com.carbon.hardness

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
    private var lastVoiceAt = 0L

    // ---- 시료/이력 ----
    var sampleName by mutableStateOf(""); private set
    var confirmingReset by mutableStateOf(false); private set

    init {
        restore()
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
                status = "${stages[s].short} 완료"
            }
        }
    }

    // ---------- 마이크 ----------
    fun onMicStateChanged(on: Boolean) {
        micOn = on
        if (on) lastVoiceAt = System.currentTimeMillis()
    }

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

    // ---------- 무게 ----------
    fun setWeight(slot: Int, value: Double) {
        if (slot !in 1..3) return
        prevValue = wState[slot - 1]
        wState[slot - 1] = value
        persistWeights()
        pendingSlot = slot
        status = "${slotName(slot)} = ${fmt(value)} g · 맞나요?"
    }

    fun confirmPending() {
        val confirmed = pendingSlot ?: return
        pendingSlot = null
        val st = stages[currentStage]
        if (st.kind == StageKind.WEIGH) {
            val remaining = st.weighSlots.filter { wState[it - 1] == null }
            if (remaining.isEmpty()) {
                if (currentStage < stages.size - 1) {
                    next()
                    status = "확정 · ${stages[currentStage].title} — \"${stages[currentStage].short} 시작\""
                } else {
                    finishRun()
                }
            } else {
                val n = remaining.first()
                status = "${slotName(confirmed)} 확정 · 이어서 ${n}번 ${slotName(n)} 말하세요"
            }
        } else {
            status = "확정"
        }
    }

    /** 계량까지 끝났을 때: 이력 자동 저장 */
    private fun finishRun() {
        val w1 = wState[0]; val w2 = wState[1]; val w3 = wState[2]
        val h = hardness
        if (w1 != null && w2 != null && w3 != null && h != null && !prefs.savedRun) {
            history.add(
                HistoryRecord(
                    System.currentTimeMillis(), sampleName,
                    w1, w2, w3, h, massErrorPct ?: 0.0
                )
            )
            prefs.savedRun = true
            status = when (massOk) {
                true -> "측정 완료 · 경도 ${fmt(h)}% · 이력에 저장됨"
                false -> "측정 완료 · 오차 초과! 재측정 권장 · 이력에 저장됨"
                null -> "측정 완료 · 이력에 저장됨"
            }
        } else {
            status = "측정 완료"
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

    fun remainingSec(stage: Int): Int {
        val t = timers[stage] ?: return stages[stage].minutes * 60
        return if (t.status == 1) ((t.end - nowMillis) / 1000).toInt().coerceAtLeast(0)
        else stages[stage].minutes * 60
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
        val min = stages[stage].minutes
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
        val vm = prefs.voiceMode
        prefs.clearAll()
        prefs.voiceMode = vm   // 모드 설정은 유지
        sampleName = ""
        status = "새 측정 시작 · 시료명을 정하고 \"거르기 시작\""
    }

    fun fmt(v: Double) = String.format(java.util.Locale.US, "%.2f", v)
}
