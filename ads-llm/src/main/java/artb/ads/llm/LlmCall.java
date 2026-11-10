package artb.ads.llm;

/** Результат вызова этапа: значение, какая модель отвечала и сколько это заняло. */
public record LlmCall<T>(T value, String model, long latencyMs) {
}
