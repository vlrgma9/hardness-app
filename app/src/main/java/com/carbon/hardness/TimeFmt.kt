package com.carbon.hardness

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object TimeFmt {
    private val hm = SimpleDateFormat("HH:mm", Locale.KOREA)

    /** epoch millis -> "14:42" */
    fun clock(millis: Long): String = hm.format(Date(millis))

    /** 남은 초 -> "21:04" */
    fun mmss(totalSec: Int): String {
        val s = totalSec.coerceAtLeast(0)
        return "%02d:%02d".format(s / 60, s % 60)
    }
}
