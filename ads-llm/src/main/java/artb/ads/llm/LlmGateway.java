package artb.ads.llm;

/** Единая точка вызова моделей для пайплайна. Тесты подменяют её фейком. */
public interface LlmGateway {

    /** Структурированный ответ по типу {@code type}. Текст пользователя маскируется перед отправкой. */
    <T> LlmCall<T> call(LlmStage stage, String system, String user, Class<T> type);

    /** Свободный текст (ответ агента в демо). Текст тоже маскируется. */
    LlmCall<String> text(LlmStage stage, String system, String user);
}
