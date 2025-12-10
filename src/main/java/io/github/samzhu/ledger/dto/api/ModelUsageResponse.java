package io.github.samzhu.ledger.dto.api;

import java.util.List;

/**
 * 模型用量查詢 API 回應。
 *
 * <p>包含指定模型在特定期間的用量統計，用於：
 * <ul>
 *   <li>成本分析 - 比較各模型的花費</li>
 *   <li>容量規劃 - 評估模型使用趨勢</li>
 * </ul>
 *
 * @param model 模型名稱
 * @param period 查詢期間
 * @param summary 期間用量摘要
 * @param daily 每日明細列表
 */
public record ModelUsageResponse(
    String model,
    DatePeriod period,
    UsageSummary summary,
    List<DailyUsage> daily
) {}
