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
    private val ctx get() = getApplication<Application>()

    var nudgeListen: () -> Unit = {}

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
    var status by mutableStateOf("듣는 중…  \"타이머 시작\" · \"일번 사십오쩜삼사\""); private set

    init {
        restore()
        viewModelScope.launch {
            while (true) {
                nowMillis = System.currentTimeMillis()
                reconcileTimers()
                delay(1000)
            }
        }
    }

    private fun restore() {
        currentStage = prefs.currentStage
        wState[0] = prefs.w1; wState[1] = prefs.w2; wState[2] = prefs.w3
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

    // ---------- 음성 진입점 ----------
    fun onPartial(text: String) { heardText = text }

    fun onFinal(text: String) {
        heardText = text
        val p = VoiceParser.parse(text)

        // 1) 명령 우선 (숫자보다 먼저 — "타이머 시작"이 무게로 오인되지 않게)
        if (p.command != null && p.number == null) {
            when (p.command) {
                Command.CONFIRM -> if (pendingSlot != null) confirmPending()
                Command.REJECT -> if (pendingSlot != null) rejectPending()
                Command.START_TIMER -> startCurrentTimer()
                Command.STOP_TIMER -> activeTimerStage?.let { stopTimer(it, byUser = true) }
                Command.NEXT -> next()
                Command.PREV -> prev()
                Command.RESET -> resetAll()
                Command.SAVE -> Unit
                else -> Unit
            }
            return
        }

        // 2) 숫자: 슬롯을 콕 집었으면 그 칸, 아니면 계량 단계일 때만 순서대로
        if (p.number != null) {
            val slot = p.slot ?: focusedSlotOrNull()
            if (slot != null) setWeight(slot, p.number)
            else status = "지금은 계량 단계가 아니에요 · \"이번 구십사쩜육사\"처럼 번호를 붙이면 기록됩니다"
        }
    }

    /** 계량 단계일 때만 맨숫자를 받을 칸을 준다. 타이머 단계에선 null(잡음 무시). */
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
                    status = "확정 · ${stages[currentStage].title} — \"시작\"이라고 말하세요"
                } else {
                    status = when (massOk) {
                        true -> "측정 완료 · 질량검증 정상"
                        false -> "측정 완료 · 오차 초과, 재측정 확인"
                        null -> "확정"
                    }
                }
            } else {
                val n = remaining.first()
                status = "${slotName(confirmed)} 확정 · 이어서 ${n}번 ${slotName(n)} 말하세요"
            }
        } else {
            status = "확정"
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

    fun editSlot(slot: Int) {
        // 다이얼 편집 시작 시 그 칸을 pending 으로
        pendingSlot = slot
    }

    private fun persistWeights() {
        prefs.w1 = wState[0]; prefs.w2 = wState[1]; prefs.w3 = wState[2]
    }

    private fun slotName(slot: Int) = slots[slot - 1].name

    // ---------- 타이머 ----------
    val activeTimerStage: Int?
        get() = Experiment.timerStageIndices.firstOrNull { (timers[it]?.status ?: 0) == 1 }

    fun timerStatus(stage: Int) = timers[stage]?.status ?: 0
    fun timerEnd(stage: Int) = timers[stage]?.end ?: 0L
    fun timerStart(stage: Int) = timers[stage]?.start ?: 0L

    /** 진행 중 타이머의 경과 분 */
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
            status = "이 단계는 타이머가 없습니다"; return
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
        AlarmScheduler.schedule(ctx, end, stage, "${min}분 ${stages[stage].short}")
        Notifications.showCountdown(ctx, "${stages[stage].short} ${min}분", end)
        status = "${stages[stage].short} 시작 · 완료 ${TimeFmt.clock(end)}"
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
    val sumLightHeavy: Double?
        get() { val a = wState[1]; val b = wState[2]; return if (a != null && b != null) a + b else null }

    val massErrorPct: Double?
        get() {
            val s = sumLightHeavy; val m0 = wState[0]
            return if (s != null && m0 != null && m0 > 0) abs(s - m0) / m0 * 100.0 else null
        }
    val massOk: Boolean? get() = massErrorPct?.let { it <= 2.0 }

    // 경도 계산식은 확정 후 구현
    val hardness: Double? get() = null

    // ---------- 초기화 ----------
    fun resetAll() {
        activeTimerStage?.let { stopTimer(it, byUser = false) }
        AlarmScheduler.cancel(ctx)
        Notifications.cancelCountdown(ctx)
        wState[0] = null; wState[1] = null; wState[2] = null
        pendingSlot = null; prevValue = null
        currentStage = 0
        timers.clear()
        prefs.clearAll()
        status = "새 측정 시작"
    }

    fun fmt(v: Double) = String.format(java.util.Locale.US, "%.2f", v)
}
