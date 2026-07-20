package com.carbon.hardness

import android.content.Context

/** 앱 상태를 기기에 저장/복원 (다른 앱 쓰다 돌아와도 이어서) */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("hardness_state", Context.MODE_PRIVATE)

    var w1: Double?
        get() = getD("w1"); set(v) = putD("w1", v)
    var w2: Double?
        get() = getD("w2"); set(v) = putD("w2", v)
    var w3: Double?
        get() = getD("w3"); set(v) = putD("w3", v)

    var currentStage: Int
        get() = sp.getInt("stage", 0)
        set(v) { sp.edit().putInt("stage", v).apply() }

    var sampleStart: Long
        get() = sp.getLong("sampleStart", 0L)
        set(v) { sp.edit().putLong("sampleStart", v).apply() }

    /** 음성 사용 여부 (false = 수동 모드) */
    var voiceMode: Boolean
        get() = sp.getBoolean("voiceMode", true)
        set(v) { sp.edit().putBoolean("voiceMode", v).apply() }

    var sampleName: String
        get() = sp.getString("sampleName", "") ?: ""
        set(v) { sp.edit().putString("sampleName", v).apply() }

    /** 이번 측정이 이력에 저장됐는지 (중복 저장 방지) */
    var savedRun: Boolean
        get() = sp.getBoolean("savedRun", false)
        set(v) { sp.edit().putBoolean("savedRun", v).apply() }

    /** 이번 측정과 연동된 이력 기록의 ts (0 = 아직 없음) */
    var currentRunTs: Long
        get() = sp.getLong("runTs", 0L)
        set(v) { sp.edit().putLong("runTs", v).apply() }

    // 타이머 단계별: status(0=idle,1=running,2=done), start, end
    fun timerStatus(stage: Int) = sp.getInt("t${stage}_st", 0)
    fun timerStart(stage: Int) = sp.getLong("t${stage}_start", 0L)
    fun timerEnd(stage: Int) = sp.getLong("t${stage}_end", 0L)
    fun setTimer(stage: Int, status: Int, start: Long, end: Long) {
        sp.edit()
            .putInt("t${stage}_st", status)
            .putLong("t${stage}_start", start)
            .putLong("t${stage}_end", end)
            .apply()
    }

    // ---- 설정 (초기화해도 유지) ----
    fun defaultWeight(slot: Int, def: Double): Double {
        val bits = sp.getLong("defW${slot}_bits", java.lang.Double.doubleToRawLongBits(def))
        return java.lang.Double.longBitsToDouble(bits)
    }
    fun setDefaultWeight(slot: Int, v: Double) {
        sp.edit().putLong("defW${slot}_bits", java.lang.Double.doubleToRawLongBits(v)).apply()
    }

    fun stageMinutes(stage: Int, def: Int) = sp.getInt("min_$stage", def)
    fun setStageMinutes(stage: Int, v: Int) { sp.edit().putInt("min_$stage", v).apply() }

    var lastUpdateCheck: Long
        get() = sp.getLong("lastUpdCheck", 0L)
        set(v) { sp.edit().putLong("lastUpdCheck", v).apply() }

    /** 측정 진행 데이터만 지운다 (설정·모드는 유지) */
    fun clearRun() {
        val e = sp.edit()
        for (k in listOf("w1_bits", "w2_bits", "w3_bits", "stage", "sampleStart", "sampleName", "savedRun", "runTs"))
            e.remove(k)
        for (s in 0..4) {
            e.remove("t${s}_st"); e.remove("t${s}_start"); e.remove("t${s}_end")
        }
        e.apply()
    }

    private fun getD(k: String): Double? {
        val bits = sp.getLong("${k}_bits", java.lang.Double.doubleToRawLongBits(Double.NaN))
        val d = java.lang.Double.longBitsToDouble(bits)
        return if (d.isNaN()) null else d
    }
    private fun putD(k: String, v: Double?) {
        val bits = java.lang.Double.doubleToRawLongBits(v ?: Double.NaN)
        sp.edit().putLong("${k}_bits", bits).apply()
    }
}
