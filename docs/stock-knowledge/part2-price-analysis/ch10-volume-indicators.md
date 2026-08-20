# 10장. 거래량 지표

> **핵심 한 줄**: 거래량은 가격 움직임에 "얼마나 많은 사람이 동의하는가"를 나타내는 신뢰도 척도다.

---

## 10.1 OBV (On-Balance Volume)

거래량을 가격 방향에 따라 **누적**해 매수/매도 압력의 추이를 추적합니다.

```
가격 상승일: OBV += 당일 거래량
가격 하락일: OBV -= 당일 거래량
가격 변화 없는 날: OBV 변화 없음
```

### 해석

| 상황 | 의미 |
|------|------|
| OBV 상승 + 가격 상승 | 강한 상승 추세 확인 |
| OBV 상승 + 가격 횡보 | 매집(Accumulation). 곧 가격 상승 가능성 |
| OBV 하락 + 가격 횡보 | 분산(Distribution). 곧 가격 하락 가능성 |
| **OBV 다이버전스** | 가격 신고가인데 OBV 신고가 아님 → 추세 약화 |

```
가격:  ──────/──── (계속 상승)
OBV:   ──/──\──── (하락 다이버전스) → 상승 신뢰도 낮음
```

---

## 10.2 VWAP (Volume Weighted Average Price)

**거래량으로 가중한 평균 가격**. 기관 트레이더의 기준 가격으로 널리 사용됩니다.

```
VWAP = Σ(Typical Price × Volume) ÷ Σ(Volume)

Typical Price = (H + L + C) ÷ 3
```

일 내(Intraday) VWAP: 장 시작부터 현재까지 누적 계산.

### 예시

| 시간 | TP | 거래량 | TP × V |
|------|-----|-------|-------|
| 09:00 | 55,000 | 10,000 | 550,000,000 |
| 09:01 | 55,100 | 5,000 | 275,500,000 |
| 09:02 | 54,900 | 8,000 | 439,200,000 |
| **합계** | | 23,000 | 1,264,700,000 |

```
VWAP = 1,264,700,000 ÷ 23,000 = 54,987원
```

---

## 10.3 VWAP의 기관 사용 방법

### 기준선으로서의 VWAP

기관 트레이더는 VWAP을 **집행 품질의 기준**으로 사용합니다.

```
내 평균 체결가 < VWAP → 좋은 집행 (시장 평균보다 싸게 샀음)
내 평균 체결가 > VWAP → 나쁜 집행 (시장 평균보다 비싸게 샀음)
```

### VWAP 전략

| 전략 | 설명 |
|------|------|
| **VWAP 매수** | 가격이 VWAP 아래일 때 매수. "시장 평균보다 싸다" |
| **VWAP 이탈** | 가격이 VWAP 위에서 강하게 유지 → 강세 확인 |
| **VWAP 거부** | 가격이 VWAP에 닿았다가 튕겨남 → 지지/저항 역할 |

### Anchored VWAP

특정 날짜(예: 실적 발표일, 급등일)를 기준으로 누적 계산하는 변형.  
"그 날 이후 평균적으로 어디서 거래됐는가"를 추적합니다.

---

## 10.4 MFI (Money Flow Index)

RSI에 거래량을 결합한 지표. "돈이 들어오는가, 나가는가"를 봅니다.

```
Typical Price (TP) = (H + L + C) ÷ 3
Raw Money Flow = TP × Volume

긍정 Money Flow: 오늘 TP > 어제 TP인 날의 합
부정 Money Flow: 오늘 TP < 어제 TP인 날의 합

Money Ratio = 긍정 MF ÷ 부정 MF
MFI = 100 − (100 ÷ (1 + Money Ratio))
```

| MFI 값 | 의미 |
|--------|------|
| **80 이상** | 과매수 (돈이 많이 들어옴) |
| **20 이하** | 과매도 (돈이 많이 나감) |

RSI와 비교:
- RSI: 가격 변화만 반영
- MFI: 가격 + **거래량** 반영 → 더 신뢰도 높음

---

## 10.5 거래량 급등 감지 (Volume Spike Detection)

monticker의 이벤트 감지기가 거래량 급등을 감지합니다.

```
기준: 오늘 거래량 > 과거 20일 평균 거래량 × N배
```

| 배수 | 중요도 |
|------|--------|
| 2배 이상 | 관심 |
| 3배 이상 | 중요 이벤트 |
| 5배 이상 | 매우 중요 (세력 개입 의심) |

```kotlin
// monticker EventDetector 예시
val avgVolume = candleRepository.avg20dVolume(stockId)
val today = candle.volume
val importance = when {
    today > avgVolume * 5 -> ImportanceScore.CRITICAL
    today > avgVolume * 3 -> ImportanceScore.HIGH
    today > avgVolume * 2 -> ImportanceScore.MEDIUM
    else -> ImportanceScore.LOW
}
```

---

## 거래량 지표 선택 가이드

| 목적 | 추천 지표 |
|------|----------|
| 추세 신뢰도 확인 | OBV |
| 기관 집행 기준 | VWAP |
| 자금 유입/유출 | MFI |
| 이상 거래 감지 | 거래량 급등 비율 |

---

## 요약

```
OBV = 거래량 누적으로 매수/매도 압력 추적
VWAP = 거래량 가중 평균가. 기관 기준선
MFI = RSI + 거래량 = 자금 흐름 지표
거래량 급등 = 세력 개입, 뉴스 반응의 신호
가격 + 거래량을 항상 함께 봐야 한다
```

← [9장](ch09-volatility-indicators.md) | → [11장](ch11-support-resistance.md)
