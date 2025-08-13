package com.polynomeer.domain.price.service;

import com.polynomeer.domain.price.model.ChartPoint;
import com.polynomeer.domain.price.repository.TimeSeriesChartRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.ZonedDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ChartQueryServiceImpl implements ChartQueryService {

    private final TimeSeriesChartRepository chartRepository;

    @Override
    public List<ChartPoint> getChart(String tickerCode, String interval, ZonedDateTime from, ZonedDateTime to) {
        return chartRepository.findChart(tickerCode, interval, from, to);
    }
}
