package com.monticker.worker.disclosure

import java.time.LocalDate
import java.time.format.DateTimeFormatter

object MockDisclosureData {
    private val fmt = DateTimeFormatter.ofPattern("yyyyMMdd")

    fun generate(): List<DartDisclosure> {
        val today = LocalDate.now().format(fmt)
        return listOf(
            DartDisclosure("20240101000001", "삼성전자",  "005930", "주요사항보고서(자기주식취득결정)", today),
            DartDisclosure("20240101000002", "SK하이닉스", "000660", "분기보고서",                    today),
            DartDisclosure("20240101000003", "NAVER",    "035420", "임원ㆍ주요주주특정증권등소유상황보고서", today),
            DartDisclosure("20240101000004", "현대차",    "005380", "주요사항보고서(유상증자결정)",      today),
            DartDisclosure("20240101000005", "LG화학",   "051910", "사업보고서",                     today),
        )
    }
}
