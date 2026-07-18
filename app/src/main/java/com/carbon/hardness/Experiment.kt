package com.carbon.hardness

enum class StageKind { TIMER, WEIGH }

data class Stage(
    val index: Int,
    val short: String,      // 진행바 짧은 이름
    val title: String,      // 활성 카드 제목
    val kind: StageKind,
    val minutes: Int = 0,
    val weighSlots: List<Int> = emptyList(),
)

/** 무게 칸 정의 (각 1회 측정) */
data class WeightSlot(val no: Int, val name: String, val detail: String)

object Experiment {
    val stages = listOf(
        Stage(0, "거르기", "중간 활성탄 거르기 · 채 2개", StageKind.TIMER, minutes = 10),
        Stage(1, "초기무게", "초기 무게 재기 (M₀)", StageKind.WEIGH, weighSlots = listOf(1)),
        Stage(2, "마모 30′", "쇠구슬 넣고 마모", StageKind.TIMER, minutes = 30),
        Stage(3, "미세체 3′", "미세체로 거르기", StageKind.TIMER, minutes = 3),
        Stage(4, "계량", "가벼운·무거운 무게 재기", StageKind.WEIGH, weighSlots = listOf(2, 3)),
    )

    val slots = listOf(
        WeightSlot(1, "초기 M₀", "거른 활성탄 10mL 부피"),
        WeightSlot(2, "무거운", "미세체 잔류 · 잔립"),
        WeightSlot(3, "가벼운", "미세체 통과 · 미분"),
    )

    val timerStageIndices = stages.filter { it.kind == StageKind.TIMER }.map { it.index }
}
