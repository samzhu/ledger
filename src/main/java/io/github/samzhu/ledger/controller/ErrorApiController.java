package io.github.samzhu.ledger.controller;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.github.samzhu.ledger.dto.api.ErrorListResponse;
import io.github.samzhu.ledger.service.ErrorQueryService;
import io.github.samzhu.ledger.service.ErrorQueryService.ErrorEvent;

/**
 * 錯誤事件 API 控制器。
 *
 * <p>提供錯誤事件查詢的 REST API 端點。
 */
@RestController
@RequestMapping("/api/v1/errors")
public class ErrorApiController {

    private static final Logger log = LoggerFactory.getLogger(ErrorApiController.class);

    private final ErrorQueryService errorQueryService;

    public ErrorApiController(ErrorQueryService errorQueryService) {
        this.errorQueryService = errorQueryService;
    }

    /**
     * 取得最近的錯誤事件。
     *
     * @param limit 最大回傳數量（預設 20）
     * @return 錯誤事件列表
     */
    @GetMapping
    public ResponseEntity<ErrorListResponse> getRecentErrors(
            @RequestParam(defaultValue = "20") int limit) {

        log.debug("Getting recent errors, limit={}", limit);

        // 限制最大查詢數量
        int effectiveLimit = Math.min(limit, 100);

        List<ErrorEvent> errors = errorQueryService.getRecentErrors(effectiveLimit);
        ErrorListResponse response = ErrorListResponse.fromErrorEvents(errors);

        return ResponseEntity.ok(response);
    }
}
