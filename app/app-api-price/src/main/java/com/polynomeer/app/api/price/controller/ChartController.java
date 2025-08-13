package com.polynomeer.app.api.price.controller;

import com.polynomeer.domain.price.model.ChartPoint;
import com.polynomeer.domain.price.service.ChartQueryService;
import com.polynomeer.shared.common.dto.CommonResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.ZonedDateTime;
import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/charts")
public class ChartController {

    private final ChartQueryService chartService;

    @GetMapping("/{tickerCode}")
    public CommonResponse<List<ChartPoint>> getChart(
            @PathVariable String tickerCode,
            @RequestParam String interval,
            @RequestParam ZonedDateTime from,
            @RequestParam ZonedDateTime to) {
        List<ChartPoint> response = chartService.getChart(tickerCode, interval, from, to);
        return new CommonResponse<>("SUCCESS", response);
    }
}
