package artb.ads.llm;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.converter.BeanOutputConverter;

import artb.ads.llm.masking.PiiMasker;

/**
 * Вызов модели этапа со структурированным ответом. Если модель умеет схему сама
 * ({@code response_format: json_schema}), схема передаётся ей; иначе инструкции формата добавляются в запрос, ответ проверяется,
 * и при ошибке делается один повтор с текстом ошибки.
 */
public class DefaultLlmGateway implements LlmGateway {

    private static final Logger log = LoggerFactory.getLogger(DefaultLlmGateway.class);

    private final StageModels models;
    private final PiiMasker masker;
    private final boolean maskingEnabled;

    public DefaultLlmGateway(StageModels models, PiiMasker masker, boolean maskingEnabled) {
        this.models = models;
        this.masker = masker;
        this.maskingEnabled = maskingEnabled;
    }

    @Override
    public <T> LlmCall<T> call(LlmStage stage, String system, String user, Class<T> type) {
        BeanOutputConverter<T> converter = new BeanOutputConverter<>(type);
        ChatModel model = models.forStage(stage);
        boolean nativeSchema = model instanceof StructuredOutputCapable s && s.supportsNativeSchema();
        String userText = nativeSchema ? mask(user) : mask(user) + "\n\n" + converter.getFormat();

        List<Message> messages = new ArrayList<>(List.of(new SystemMessage(system), new UserMessage(userText)));
        long started = System.nanoTime();
        RuntimeException lastError = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            Prompt prompt = new Prompt(List.copyOf(messages));
            ChatResponse response = nativeSchema
                    ? ((StructuredOutputCapable) model).call(prompt, converter.getJsonSchema())
                    : model.call(prompt);
            String text = response.getResult().getOutput().getText();
            try {
                T value = converter.convert(extractJson(text));
                if (value == null) {
                    throw new IllegalArgumentException("пустой ответ");
                }
                return new LlmCall<>(value, models.idFor(stage), elapsedMs(started));
            } catch (RuntimeException e) {
                lastError = e;
                log.warn("Этап {}: ответ не прошёл схему (попытка {}): {}", stage, attempt + 1, e.getMessage());
                messages.add(new UserMessage("Предыдущий ответ не прошёл проверку схемы: " + e.getMessage()
                        + "\nВерни только JSON строго по схеме, без пояснений."));
            }
        }
        throw new LlmException("Этап " + stage + ": модель не вернула ответ по схеме", lastError);
    }

    @Override
    public LlmCall<String> text(LlmStage stage, String system, String user) {
        long started = System.nanoTime();
        ChatModel model = models.forStage(stage);
        ChatResponse response = model.call(new Prompt(List.of(new SystemMessage(system), new UserMessage(mask(user)))));
        return new LlmCall<>(response.getResult().getOutput().getText(), models.idFor(stage), elapsedMs(started));
    }

    /**
     * Вырезает JSON из ответа модели. GigaChat при схеме в промпте часто оборачивает JSON
     * в обратные кавычки, причём не всегда парные (```{…}`), а парсер Spring AI снимает только
     * правильные блоки ```json … ```. Берём текст от первой открывающей скобки до последней закрывающей.
     */
    static String extractJson(String text) {
        if (text == null) {
            return null;
        }
        int obj = text.indexOf('{');
        int arr = text.indexOf('[');
        int start = obj < 0 ? arr : arr < 0 ? obj : Math.min(obj, arr);
        if (start < 0) {
            return text;
        }
        int end = text.lastIndexOf(text.charAt(start) == '{' ? '}' : ']');
        return end > start ? text.substring(start, end + 1) : text;
    }

    private String mask(String text) {
        return maskingEnabled ? masker.mask(text) : text;
    }

    private static long elapsedMs(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }
}
