package artb.ads.llm;

import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Настройки слоя моделей.
 *
 * <pre>
 * ads.llm:
 *   default-stage: { provider: claude-cli, model: haiku }
 *   stages:
 *     judge: { provider: claude-cli, model: sonnet }
 * </pre>
 */
@ConfigurationProperties("ads.llm")
public class LlmProperties {

    /** Модель по умолчанию для этапов, которые не перечислены в {@link #stages}. */
    private StageModel defaultStage = new StageModel("internal", null);

    /** Переопределения по этапам: ключ — имя этапа в нижнем регистре (gate, intent, judge, ingestion, agent). */
    private Map<String, StageModel> stages = new HashMap<>();

    private Internal internal = new Internal();
    private Recording recording = new Recording();
    private Masking masking = new Masking();
    private Embedding embedding = new Embedding();

    public StageModel modelFor(LlmStage stage) {
        StageModel override = stages.get(stage.key());
        if (override == null) {
            return defaultStage;
        }
        return new StageModel(
                override.provider() != null ? override.provider() : defaultStage.provider(),
                override.model() != null ? override.model() : defaultStage.model());
    }

    public record StageModel(String provider, String model) {
        public StageModel {
            model = model == null || model.isBlank() ? null : model;
        }

        public String id() {
            return model == null ? provider : provider + ":" + model;
        }
    }

    /**
     * OpenAI-совместимый сервер: в проде — внутри контура (vLLM, внутренний Qwen), при отладке —
     * локальный llm-cli-proxy поверх Claude Code CLI или Qwen Code CLI.
     */
    public static class Internal {
        private String baseUrl = "http://localhost:8000/v1";
        private String apiKey = "none";
        private Duration timeout = Duration.ofSeconds(30);
        /** Повторы SDK. Для llm-cli-proxy — 0: прокси сам повторяет зависший вызов, а лишний вызов CLI дорог. */
        private int maxRetries = 2;
        /** Передавать JSON-схему как response_format (vLLM и llm-cli-proxy поддерживают); иначе — инструкцией в промпте. */
        private boolean nativeSchema = true;

        public int getMaxRetries() { return maxRetries; }
        public void setMaxRetries(int maxRetries) { this.maxRetries = maxRetries; }
        public boolean isNativeSchema() { return nativeSchema; }
        public void setNativeSchema(boolean nativeSchema) { this.nativeSchema = nativeSchema; }

        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public Duration getTimeout() { return timeout; }
        public void setTimeout(Duration timeout) { this.timeout = timeout; }
    }

    public static class Recording {
        /** off | record | replay */
        private String mode = "off";
        private Path dir = Path.of("llm-cassettes");

        public String getMode() { return mode; }
        public void setMode(String mode) { this.mode = mode; }
        public Path getDir() { return dir; }
        public void setDir(Path dir) { this.dir = dir; }
    }

    public static class Masking {
        /** В проде маскирование не отключается; флаг нужен только для тестов самого маскировщика. */
        private boolean enabled = true;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
    }

    public static class Embedding {
        /** hashing | internal */
        private String provider = "hashing";
        private String model;
        private int dimensions = 384;

        public String getProvider() { return provider; }
        public void setProvider(String provider) { this.provider = provider; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public int getDimensions() { return dimensions; }
        public void setDimensions(int dimensions) { this.dimensions = dimensions; }
    }

    public StageModel getDefaultStage() { return defaultStage; }
    public void setDefaultStage(StageModel defaultStage) { this.defaultStage = defaultStage; }
    public Map<String, StageModel> getStages() { return stages; }
    public void setStages(Map<String, StageModel> stages) { this.stages = stages; }
    public Internal getInternal() { return internal; }
    public void setInternal(Internal internal) { this.internal = internal; }
    public Recording getRecording() { return recording; }
    public void setRecording(Recording recording) { this.recording = recording; }
    public Masking getMasking() { return masking; }
    public void setMasking(Masking masking) { this.masking = masking; }
    public Embedding getEmbedding() { return embedding; }
    public void setEmbedding(Embedding embedding) { this.embedding = embedding; }
}
