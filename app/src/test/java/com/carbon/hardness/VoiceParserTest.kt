package com.carbon.hardness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VoiceParserTest {

    private fun w(s: String) = VoiceParser.parseWeight(s)

    @Test fun koreanSpokenDecimal() {
        assertEquals(59.34, w("오십구쩜삼사")!!, 0.001)
        assertEquals(59.34, w("오십구 점 삼사")!!, 0.001)
        assertEquals(47.02, w("사십칠 점 영이")!!, 0.001)
        assertEquals(50.5, w("오십 점 오")!!, 0.001)
    }

    @Test fun arabicAndMixed() {
        assertEquals(59.34, w("59.34")!!, 0.001)
        assertEquals(59.34, w("59점34")!!, 0.001)
        assertEquals(49.0, w("49")!!, 0.001)
    }

    @Test fun nativeKorean() {
        assertEquals(49.34, w("마흔아홉 점 삼사")!!, 0.001)
        assertEquals(50.0, w("쉰")!!, 0.001)
    }

    @Test fun grams() {
        assertEquals(48.7, w("사십팔 점 칠 그램")!!, 0.001)
    }

    @Test fun slotAddressing() {
        val a = VoiceParser.parse("일번 사십오쩜삼사")
        assertEquals(1, a.slot); assertEquals(45.34, a.number!!, 0.001)

        val b = VoiceParser.parse("삼번 사십삼 점 팔팔")
        assertEquals(3, b.slot); assertEquals(43.88, b.number!!, 0.001)

        val c = VoiceParser.parse("2번 1점2")
        assertEquals(2, c.slot); assertEquals(1.2, c.number!!, 0.001)

        // 슬롯 없는 순수 무게
        val d = VoiceParser.parse("사십오쩜삼사")
        assertNull(d.slot); assertEquals(45.34, d.number!!, 0.001)
    }

    @Test fun commands() {
        assertEquals(Command.CONFIRM, VoiceParser.parse("응").command)
        assertEquals(Command.CONFIRM, VoiceParser.parse("맞아").command)
        assertEquals(Command.REJECT, VoiceParser.parse("아니").command)
        assertEquals(Command.REJECT, VoiceParser.parse("취소").command)
        assertEquals(Command.START_TIMER, VoiceParser.parse("타이머 시작").command)
        assertEquals(Command.START_TIMER, VoiceParser.parse("시작").command)
        assertEquals(Command.STOP_TIMER, VoiceParser.parse("정지").command)
        assertEquals(Command.NEXT, VoiceParser.parse("다음").command)
    }

    @Test fun weightUtteranceIsNotCommand() {
        assertNull(VoiceParser.parse("삼번 사십삼쩜팔팔").command)
    }

    @Test fun commandIsNotWeight() {
        // "타이머 시작"의 '이'가 2로 오인 기록되던 버그 회귀 방지
        assertNull(VoiceParser.parse("타이머 시작").number)
        assertNull(VoiceParser.parse("시작").number)
        assertNull(VoiceParser.parse("정지").number)
        // 슬롯을 콕 집으면 숫자가 우선
        val p = VoiceParser.parse("이번 구십사쩜육사")
        assertEquals(2, p.slot)
        assertEquals(94.64, p.number!!, 0.001)
    }

    @Test fun stageNamedTimer() {
        val a = VoiceParser.parse("거르기 시작")
        assertEquals(Command.START_TIMER, a.command); assertEquals(0, a.stageRef); assertNull(a.number)
        val b = VoiceParser.parse("쇠구슬 시작")
        assertEquals(Command.START_TIMER, b.command); assertEquals(2, b.stageRef)
        val c = VoiceParser.parse("마모 시작")
        assertEquals(Command.START_TIMER, c.command); assertEquals(2, c.stageRef)
        val d = VoiceParser.parse("체진동기 시작")
        assertEquals(Command.START_TIMER, d.command); assertEquals(3, d.stageRef)
    }

    @Test fun sampleName() {
        assertEquals("에이 칠", VoiceParser.parse("시료 에이 칠").sampleName)
        // 이름 속 '칠'이 무게 7로 오인되면 안 됨
        assertNull(VoiceParser.parse("시료 에이 칠").number)
    }

    @Test fun noise() {
        assertNull(w("안녕하세요"))
        assertNull(w("확인"))
    }
}
