package com.monticker.api.wallet.domain

enum class BehaviorGrade(val label: String) {
    POOR("위험"),
    FAIR("보통"),
    GOOD("양호"),
    GREAT("우수"),
    EXCELLENT("탁월");

    companion object {
        fun fromScore(score: Int): BehaviorGrade = when {
            score >= 90 -> EXCELLENT
            score >= 80 -> GREAT
            score >= 60 -> GOOD
            score >= 40 -> FAIR
            else        -> POOR
        }
    }
}
