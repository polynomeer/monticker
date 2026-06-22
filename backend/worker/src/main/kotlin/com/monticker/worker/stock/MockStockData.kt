package com.monticker.worker.stock

object MockStockData {
    val stocks = listOf(
        // KOSPI
        KrxStockItem("005930", "삼성전자",          "KOSPI",  "전기전자"),
        KrxStockItem("000660", "SK하이닉스",         "KOSPI",  "전기전자"),
        KrxStockItem("005380", "현대차",             "KOSPI",  "운수장비"),
        KrxStockItem("005490", "POSCO홀딩스",        "KOSPI",  "철강금속"),
        KrxStockItem("051910", "LG화학",             "KOSPI",  "화학"),
        KrxStockItem("006400", "삼성SDI",            "KOSPI",  "전기전자"),
        KrxStockItem("035420", "NAVER",             "KOSPI",  "서비스업"),
        KrxStockItem("000270", "기아",               "KOSPI",  "운수장비"),
        KrxStockItem("068270", "셀트리온",            "KOSPI",  "의약품"),
        KrxStockItem("105560", "KB금융",             "KOSPI",  "금융업"),
        KrxStockItem("055550", "신한지주",            "KOSPI",  "금융업"),
        KrxStockItem("003550", "LG",                "KOSPI",  "전기전자"),
        KrxStockItem("012330", "현대모비스",           "KOSPI",  "운수장비"),
        KrxStockItem("028260", "삼성물산",            "KOSPI",  "유통업"),
        KrxStockItem("066570", "LG전자",             "KOSPI",  "전기전자"),
        // KOSDAQ
        KrxStockItem("035720", "카카오",             "KOSDAQ", "서비스업"),
        KrxStockItem("247540", "에코프로비엠",         "KOSDAQ", "화학"),
        KrxStockItem("086520", "에코프로",            "KOSDAQ", "화학"),
        KrxStockItem("373220", "LG에너지솔루션",       "KOSPI",  "전기전자"),
        KrxStockItem("000100", "유한양행",            "KOSPI",  "의약품"),
    )
}
