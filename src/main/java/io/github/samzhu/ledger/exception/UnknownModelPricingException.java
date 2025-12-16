package io.github.samzhu.ledger.exception;

/**
 * 未知模型定價異常。
 *
 * <p>當遇到沒有配置定價的模型名稱時拋出此異常。
 * 這與 error 事件的 model=null 不同，null 是允許的。
 *
 * <p>處理方式：
 * <ul>
 *   <li>批次處理會中止該批次</li>
 *   <li>批次不會被標記為 processed，可在新增定價後重試</li>
 *   <li>應在 application.yaml 新增該模型的定價配置</li>
 * </ul>
 */
public class UnknownModelPricingException extends RuntimeException {

    private final String modelName;
    private final String eventId;

    public UnknownModelPricingException(String modelName, String eventId) {
        super(String.format("Unknown model pricing: model='%s', eventId='%s'. " +
            "Please add pricing configuration in application.yaml", modelName, eventId));
        this.modelName = modelName;
        this.eventId = eventId;
    }

    public String getModelName() {
        return modelName;
    }

    public String getEventId() {
        return eventId;
    }
}
