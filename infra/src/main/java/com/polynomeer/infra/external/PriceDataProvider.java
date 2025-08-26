package com.polynomeer.infra.external;

import java.util.List;

public interface PriceDataProvider {
    /**
     * 입력된 티커들의 현재 시세 스냅샷을 반환한다.
     * 구현체는 병렬/직렬 호출, 배치 크기, 파싱 전략을 자유롭게 선택.
     */
    List<PriceSnapshot> fetchSnapshots(List<String> tickers);
}
