package com.monticker.api.common.calendar

import java.time.LocalDate

/**
 * 테스트용 — V83 마이그레이션 파일의 시드를 그대로 읽어 캘린더를 만든다.
 * 테스트가 따로 휴장일 목록을 들고 있으면 시드를 고쳐도 테스트는 옛 목록으로 통과한다. 그래서 같은 파일을 읽는다.
 */
object V83Seed {
    private val HOLIDAY = Regex("""\('KRX',\s*DATE '(\d{4}-\d{2}-\d{2})',\s*'([^']+)',\s*'([A-Z_]+)'\)""")
    private val YEAR = Regex("""\('KRX',\s*(\d{4}),\s*(TRUE|FALSE)""")

    private val sql: String by lazy {
        requireNotNull(javaClass.classLoader.getResource("db/migration/V83__market_holidays.sql")) { "V83 not on classpath" }.readText()
    }

    val holidays: List<MarketHoliday> by lazy {
        HOLIDAY.findAll(sql).map { MarketHoliday(LocalDate.parse(it.groupValues[1]), it.groupValues[2], it.groupValues[3]) }.toList()
    }

    val years: Set<Int> by lazy { YEAR.findAll(sql).map { it.groupValues[1].toInt() }.toSet() }

    fun calendar(onUncoveredYear: (Int) -> Unit = {}) = KrxCalendar(holidays, years, onUncoveredYear)
}
