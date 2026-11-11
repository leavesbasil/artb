package artb.ads.llm;

import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;

/** Модель, которая сама гарантирует ответ по JSON-схеме (например, сервер с {@code response_format: json_schema}). */
public interface StructuredOutputCapable {

    ChatResponse call(Prompt prompt, String jsonSchema);

    default boolean supportsNativeSchema() {
        return true;
    }
}
