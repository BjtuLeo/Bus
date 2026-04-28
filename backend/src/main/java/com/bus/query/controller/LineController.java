package com.bus.query.controller;

import com.bus.query.model.LineMapResponse;
import com.bus.query.model.LineSearchItem;
import com.bus.query.service.TflLineService;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/lines")
public class LineController {

    private final TflLineService tflLineService;

    public LineController(TflLineService tflLineService) {
        this.tflLineService = tflLineService;
    }

    @GetMapping("/search")
    public List<LineSearchItem> search(@RequestParam("q") @NotBlank String query) {
        return tflLineService.searchLines(query);
    }

    @GetMapping("/{lineId}/map")
    public LineMapResponse lineMap(@PathVariable String lineId) {
        return tflLineService.getLineMap(lineId);
    }
}
