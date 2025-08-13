package com.polynomeer.domain.price.service;

import com.polynomeer.domain.price.model.ChartPoint;

import java.time.ZonedDateTime;
import java.util.List;

public interface ChartQueryService {
    List<ChartPoint> getChart(String tickerCode, String interval, ZonedDateTime from, ZonedDateTime to);
}
