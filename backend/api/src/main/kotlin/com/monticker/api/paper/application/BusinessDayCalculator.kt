package com.monticker.api.paper.application

import java.time.DayOfWeek
import java.time.LocalDate

object BusinessDayCalculator {

    fun addBusinessDays(from: LocalDate, days: Int): LocalDate {
        var date = from
        var remaining = days
        while (remaining > 0) {
            date = date.plusDays(1)
            if (date.dayOfWeek !in setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)) {
                remaining--
            }
        }
        return date
    }
}
