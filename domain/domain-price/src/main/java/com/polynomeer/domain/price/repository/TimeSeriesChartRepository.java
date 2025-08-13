package com.polynomeer.domain.price.repository;

import com.polynomeer.domain.price.model.ChartPoint;

import java.time.ZonedDateTime;
import java.util.List;

public interface TimeSeriesChartRepository {
    List<ChartPoint> findChart(String tickerCode, String interval, ZonedDateTime from, ZonedDateTime to);
}
