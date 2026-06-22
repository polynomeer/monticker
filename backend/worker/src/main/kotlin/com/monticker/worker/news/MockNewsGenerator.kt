package com.monticker.worker.news

object MockNewsGenerator {
    private val templates = listOf(
        "{name}, 52주 신고가 돌파… 외국인 매수세 집중",
        "{name} 실적 서프라이즈… 영업이익 전년比 30% 증가",
        "{name} 신제품 발표 임박… 기대감에 강세",
        "{name}, 경쟁사 대비 점유율 확대 전망",
        "증권가, {name} 목표주가 상향… '매수' 유지",
        "{name} 대규모 자사주 매입 결정",
        "{name} 글로벌 수주 계약 체결 소식",
    )

    fun generate(stockName: String, count: Int = 5): List<NaverNewsItem> {
        return templates.shuffled().take(count).mapIndexed { i, template ->
            NaverNewsItem(
                title       = template.replace("{name}", stockName),
                description = "${stockName} 관련 최신 시장 동향 및 분석 내용입니다.",
                link        = "https://mock-news.example.com/${stockName.hashCode()}-$i",
                pubDate     = "Mon, 01 Jan 2024 09:00:00 +0900",
                source      = listOf("한국경제", "매일경제", "머니투데이", "연합뉴스").random(),
            )
        }
    }
}
