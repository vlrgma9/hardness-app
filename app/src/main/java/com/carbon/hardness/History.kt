package com.carbon.hardness

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

data class HistoryRecord(
    val ts: Long,
    val name: String,
    val w1: Double, val w2: Double, val w3: Double,
    val hardness: Double,
    val errPct: Double,
)

/** 측정 이력 (JSON, 기기 내 저장) */
class History(context: Context) {
    private val sp = context.getSharedPreferences("hardness_history", Context.MODE_PRIVATE)

    fun add(r: HistoryRecord) {
        val arr = load()
        arr.put(JSONObject().apply {
            put("ts", r.ts); put("name", r.name)
            put("w1", r.w1); put("w2", r.w2); put("w3", r.w3)
            put("h", r.hardness); put("e", r.errPct)
        })
        sp.edit().putString("records", arr.toString()).apply()
    }

    fun all(): List<HistoryRecord> {
        val arr = load()
        val out = ArrayList<HistoryRecord>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out.add(
                HistoryRecord(
                    o.getLong("ts"), o.optString("name", ""),
                    o.getDouble("w1"), o.getDouble("w2"), o.getDouble("w3"),
                    o.getDouble("h"), o.getDouble("e")
                )
            )
        }
        return out.sortedByDescending { it.ts }
    }

    fun today(): List<HistoryRecord> {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        return all().filter { it.ts >= cal.timeInMillis }
    }

    fun lastDays(days: Int): List<HistoryRecord> {
        val from = System.currentTimeMillis() - days * 86_400_000L
        return all().filter { it.ts >= from }
    }

    private fun load(): JSONArray =
        try { JSONArray(sp.getString("records", "[]")) } catch (_: Exception) { JSONArray() }

    companion object {
        private val d = SimpleDateFormat("yyyy-MM-dd", Locale.KOREA)
        private val t = SimpleDateFormat("HH:mm", Locale.KOREA)

        /** 엑셀에 붙여넣으면 열이 맞는 탭 구분 텍스트 */
        fun toTsv(records: List<HistoryRecord>): String {
            val sb = StringBuilder("날짜\t시각\t시료\t초기 M0(g)\t무거운(g)\t가벼운(g)\t경도(%)\t질량오차(%)\n")
            for (r in records.sortedBy { it.ts }) {
                val date = Date(r.ts)
                sb.append(d.format(date)).append('\t').append(t.format(date)).append('\t')
                    .append(r.name.ifBlank { "-" }).append('\t')
                    .append(f(r.w1)).append('\t').append(f(r.w2)).append('\t').append(f(r.w3)).append('\t')
                    .append(f(r.hardness)).append('\t').append(f(r.errPct)).append('\n')
            }
            return sb.toString()
        }

        private fun f(v: Double) = String.format(Locale.US, "%.2f", v)
    }
}
