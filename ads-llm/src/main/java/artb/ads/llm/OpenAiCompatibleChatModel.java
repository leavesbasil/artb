package artb.ads.llm;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;

/**
 * Модель на OpenAI-совместимом сервере (vLLM внутри контура, локальный llm-cli-proxy).
 * JSON-схема передаётся как {@code response_format: json_schema}, если сервер это поддерживает.
 * Модель в опциях вызова указывается явно: опции без модели Spring AI дополняет своей моделью
 * по умолчанию, а не моделью клиента.
 */
public class OpenAiCompatibleChatModel implements ChatModel, StructuredOutputCapable {

    private final ChatModel delegate;
    private final String model;
    private final boolean nativeSchema;

    public OpenAiCompatibleChatModel(ChatModel delegate, String model, boolean nativeSchema) {
        this.delegate = delegate;
        this.model = model;
        this.nativeSchema = nativeSchema;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        return delegate.call(prompt);
    }

    @Override
    public ChatResponse call(Prompt prompt, String jsonSchema) {
        return delegate.call(new Prompt(prompt.getInstructions(),
                OpenAiChatOptions.builder().model(model).outputSchema(jsonSchema).build()));
    }

    @Override
    public boolean supportsNativeSchema() {
        return nativeSchema;
    }
}
