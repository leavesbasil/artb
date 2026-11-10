package artb.ads.llm.recording;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import artb.ads.llm.LlmException;
import artb.ads.llm.StructuredOutputCapable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Декоратор записи и воспроизведения ответов модели. Ключ — хэш модели, сообщений и схемы.
 * В режиме {@code replay} отсутствие записи — ошибка, а не обращение к модели: тесты в CI
 * не ходят в сеть и детерминированы.
 */
public class RecordingChatModel implements ChatModel, StructuredOutputCapable {

    public enum Mode { OFF, RECORD, REPLAY;

        public static Mode parse(String value) {
            return Mode.valueOf(value.trim().toUpperCase());
        }
    }

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final ChatModel delegate;
    private final String modelId;
    private final Mode mode;
    private final Path dir;

    public RecordingChatModel(ChatModel delegate, String modelId, Mode mode, Path dir) {
        this.delegate = delegate;
        this.modelId = modelId;
        this.mode = mode;
        this.dir = dir;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        return callInternal(prompt, null);
    }

    @Override
    public ChatResponse call(Prompt prompt, String jsonSchema) {
        return callInternal(prompt, jsonSchema);
    }

    @Override
    public boolean supportsNativeSchema() {
        return delegate instanceof StructuredOutputCapable s && s.supportsNativeSchema();
    }

    private ChatResponse callInternal(Prompt prompt, String jsonSchema) {
        if (mode == Mode.OFF) {
            return invoke(prompt, jsonSchema);
        }
        String key = key(prompt, jsonSchema);
        Path file = dir.resolve(key + ".json");
        if (Files.exists(file)) {
            return fromText(read(file));
        }
        if (mode == Mode.REPLAY) {
            throw new LlmException("Нет записи ответа модели " + modelId + " для ключа " + key
                    + " в " + dir + ". Перезапишите кассеты в режиме record.");
        }
        ChatResponse response = invoke(prompt, jsonSchema);
        write(file, key, response.getResult().getOutput().getText());
        return response;
    }

    private ChatResponse invoke(Prompt prompt, String jsonSchema) {
        if (jsonSchema != null && delegate instanceof StructuredOutputCapable s) {
            return s.call(prompt, jsonSchema);
        }
        return delegate.call(prompt);
    }

    String key(Prompt prompt, String jsonSchema) {
        StringBuilder sb = new StringBuilder(modelId).append('\n');
        for (Message m : prompt.getInstructions()) {
            sb.append(m.getMessageType()).append(':').append(m.getText()).append('\n');
        }
        if (jsonSchema != null) {
            sb.append("schema:").append(jsonSchema);
        }
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash, 0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static ChatResponse fromText(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    private static String read(Path file) {
        try {
            JsonNode node = JSON.readTree(Files.readString(file, StandardCharsets.UTF_8));
            return node.get("text").asString();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void write(Path file, String key, String text) {
        try {
            Files.createDirectories(dir);
            Map<String, Object> doc = new LinkedHashMap<>();
            doc.put("model", modelId);
            doc.put("key", key);
            doc.put("recordedAt", Instant.now().toString());
            doc.put("text", text);
            Files.writeString(file, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(doc), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
