package com.monticker.api.stock.infrastructure

import com.monticker.api.stock.domain.Stock
import org.springframework.data.annotation.Id
import org.springframework.data.elasticsearch.annotations.Document
import org.springframework.data.elasticsearch.annotations.Field
import org.springframework.data.elasticsearch.annotations.FieldType
import org.springframework.data.elasticsearch.annotations.Setting

/**
 * Elasticsearch 인덱스 도큐먼트.
 *
 * 인덱스 설정:
 *  - nori_analyzer: 한국어 형태소 분석 (삼성전자 → 삼성 / 전자)
 *  - autocomplete:  edge_ngram(1-10) — 앞글자 자동완성 (삼 → 삼성, 삼성전, ...)
 *  - symbol 필드:   keyword + text 멀티필드 (코드 완전 일치 우선)
 */
@Document(indexName = "stocks")
@Setting(settingPath = "elasticsearch/stock-index-settings.json")
data class StockDocument(
    @Id
    val id: String,               // Stock.id.toString()

    @Field(type = FieldType.Text, analyzer = "autocomplete", searchAnalyzer = "nori_analyzer")
    val name: String,

    @Field(type = FieldType.Keyword)
    val symbol: String,

    @Field(type = FieldType.Keyword)
    val market: String,

    @Field(type = FieldType.Keyword)
    val exchange: String,

    @Field(type = FieldType.Text, analyzer = "nori_analyzer")
    val sector: String? = null,

    @Field(type = FieldType.Text, analyzer = "nori_analyzer")
    val industry: String? = null,

    @Field(type = FieldType.Boolean)
    val isActive: Boolean = true,
) {
    companion object {
        fun from(stock: Stock) = StockDocument(
            id       = stock.id.toString(),
            name     = stock.name,
            symbol   = stock.symbol,
            market   = stock.market.name,
            exchange = stock.exchange,
            sector   = stock.sector,
            industry = stock.industry,
            isActive = stock.isActive,
        )
    }
}
