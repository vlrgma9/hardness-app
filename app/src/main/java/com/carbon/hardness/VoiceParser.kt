package com.carbon.hardness

/** 음성 명령 종류 */
enum class Command { START_TIMER, STOP_TIMER, NEXT, PREV, CONFIRM, REJECT, SAVE, RESET }

/**
 * 파싱 결과.
 * - slot  : "일번/이번/삼번" 처럼 콕 집은 칸 번호(1~3), 없으면 null
 * - number: 무게 값, 없으면 null
 * - command: 명령, 없으면 null
 * - stageRef: "거르기/쇠구슬/체진동기"처럼 단계를 콕 집은 경우 그 단계 index
 * - sampleName: "시료 에이칠" → "에이칠"
 */
data class ParsedVoice(
    val command: Command?,
    val slot: Int?,
    val number: Double?,
    val stageRef: Int? = null,
    val sampleName: String? = null,
)

/**
 * 한국어 음성 → 숫자/슬롯/명령 파서.
 *   "일번 사십오쩜삼사" -> slot=1, number=45.34
 *   "삼번 사십삼 점 팔팔" -> slot=3, number=43.88
 *   "응" / "맞아"        -> CONFIRM
 *   "아니" / "취소"       -> REJECT
 *   "타이머 시작"        -> START_TIMER
 */
object VoiceParser {

    private val sinoDigit = mapOf(
        '영' to 0, '공' to 0, '일' to 1, '이' to 2, '삼' to 3, '사' to 4,
        '오' to 5, '육' to 6, '륙' to 6, '칠' to 7, '팔' to 8, '구' to 9
    )
    private val sinoUnit = mapOf('십' to 10, '백' to 100, '천' to 1000)

    private val nativeReplacements = listOf(
        "마흔" to "사십", "쉰" to "오십", "예순" to "육십",
        "서른" to "삼십", "스물" to "이십", "열" to "십",
        "하나" to "일", "둘" to "이", "셋" to "삼", "넷" to "사",
        "다섯" to "오", "여섯" to "육", "일곱" to "칠", "여덟" to "팔", "아홉" to "구"
    )

    // "<수>번" 슬롯 접두어. 무게칸은 3개(1~3)만.
    private val slotRegex = Regex("^\\s*(일|이|삼|1|2|3)\\s*번")

    fun parse(raw: String): ParsedVoice {
        val text = raw.trim()

        // "시료 ○○" — 시료명 입력. 숫자 파싱보다 먼저 (이름 속 '칠' 등이 무게로 오인 방지)
        if (text.replace(" ", "").startsWith("시료")) {
            val name = text.removePrefix("시료").trim().removePrefix("이름").trim()
            if (name.isNotBlank()) return ParsedVoice(null, null, null, null, name)
        }

        val (slot, rest) = extractSlot(text)
        val command = detectCommand(text)
        val stageRef = detectStageRef(text)
        // 명령이 인식되면 숫자 해석은 버린다 ("타이머 시작"의 '이'가 2로 오인되는 것 방지).
        // 단, "이번 구십사쩜육사"처럼 슬롯을 콕 집었으면 숫자가 우선.
        val number = if ((command != null || stageRef != null) && slot == null) null else parseWeight(rest)
        return ParsedVoice(command, slot, number, stageRef)
    }

    /** "거르기 시작", "쇠구슬 시작", "체진동기 시작" 처럼 단계를 콕 집은 경우 */
    private fun detectStageRef(raw: String): Int? {
        val s = raw.replace(" ", "")
        return when {
            s.contains("거르기") -> 0
            s.contains("쇠구슬") || s.contains("마모") -> 2
            s.contains("체진동기") || s.contains("진동기") || s.contains("미세체") -> 3
            else -> null
        }
    }

    private fun extractSlot(text: String): Pair<Int?, String> {
        val m = slotRegex.find(text) ?: return null to text
        val token = m.groupValues[1]
        val n = when (token) {
            "일", "1" -> 1; "이", "2" -> 2; "삼", "3" -> 3
            else -> null
        }
        val rest = text.removeRange(m.range).trim()
        return n to rest
    }

    fun detectCommand(raw: String): Command? {
        val s = raw.replace(" ", "")
        return when {
            s.contains("아니") || s.contains("아냐") || s.contains("취소") ||
                s.contains("틀려") || s.contains("잘못") || s.contains("지워") -> Command.REJECT
            s == "응" || s == "어" || s.contains("맞아") || s.contains("맞아요") ||
                s.contains("오케이") || s.contains("확인") || s.contains("좋아") ||
                s.contains("그래") -> Command.CONFIRM
            s.contains("정지") || s.contains("중지") || s.contains("멈춰") ||
                s.contains("멈춤") || s.contains("꺼") -> Command.STOP_TIMER
            s.contains("타이머") || s.contains("시작") || s.contains("울려") -> Command.START_TIMER
            s.contains("다음") -> Command.NEXT
            s.contains("이전") || s.contains("뒤로") -> Command.PREV
            s.contains("저장") -> Command.SAVE
            s.contains("초기화") || s.contains("리셋") -> Command.RESET
            else -> null
        }
    }

    /** 문장에서 무게(g)를 뽑아낸다. 없으면 null */
    fun parseWeight(raw: String): Double? {
        var s = raw
            .replace("기록", "").replace("무게", "").replace("그램", "")
            .replace("그람", "").replace("킬로", "").replace("저장", "")
        s = s.lowercase().replace("g", "")
        s = s.replace("쩜", "점").replace("콤마", "점").replace(".", "점").replace(",", "점")
        for ((n, r) in nativeReplacements) s = s.replace(n, r)
        s = s.replace(" ", "")
        if (s.isEmpty()) return null

        val parts = s.split("점", limit = 2)
        val intVal = parseIntegerPart(parts[0]) ?: return null
        val fracStr = if (parts.size > 1) parseFractionDigits(parts[1]) else ""

        val result = if (fracStr.isEmpty()) intVal.toDouble()
        else "$intVal.$fracStr".toDoubleOrNull() ?: return null

        if (result < 0.0 || result > 1000.0) return null
        return Math.round(result * 100.0) / 100.0
    }

    private fun parseIntegerPart(t: String): Int? {
        if (t.isEmpty()) return 0
        if (t.all { it.isDigit() }) return t.toIntOrNull()
        if (t.any { it.isDigit() } && t.none { it in sinoUnit }) {
            val d = t.filter { it.isDigit() }
            if (d.isNotEmpty()) return d.toIntOrNull()
        }
        var total = 0
        var cur = 0
        var sawAny = false
        for (ch in t) {
            when {
                ch.isDigit() -> { cur = cur * 10 + (ch - '0'); sawAny = true }
                ch in sinoDigit -> { cur = sinoDigit[ch]!!; sawAny = true }
                ch in sinoUnit -> {
                    if (cur == 0) cur = 1
                    total += cur * sinoUnit[ch]!!
                    cur = 0
                    sawAny = true
                }
                else -> {}
            }
        }
        total += cur
        return if (sawAny) total else null
    }

    private fun parseFractionDigits(t: String): String {
        val sb = StringBuilder()
        for (ch in t) {
            when {
                ch.isDigit() -> sb.append(ch)
                ch in sinoDigit -> sb.append(sinoDigit[ch]!!)
            }
        }
        return sb.toString()
    }
}
