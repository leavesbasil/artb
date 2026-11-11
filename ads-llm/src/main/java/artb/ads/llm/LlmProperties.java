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
 *   default-stage: { provider: gigachat, model: GigaChat-2 }
 *   stages:
 *     judge: { provider: gigachat, model: GigaChat-2-Max }
 * </pre>
 */
@ConfigurationProperties("ads.llm")
public class LlmProperties {

    /** Модель по умолчанию для этапов, которые не перечислены в {@link #stages}. */
    private StageModel defaultStage = new StageModel("gigachat", null);

    /** Переопределения по этапам: ключ — имя этапа в нижнем регистре (gate, intent, judge, ingestion, agent). */
    private Map<String, StageModel> stages = new HashMap<>();

    private GigaChat gigachat = new GigaChat();
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
     * Любой OpenAI-совместимый сервер (провайдер internal): gpt2giga перед GigaChat, vLLM и т. п.
     */
    public static class Internal {
        private String baseUrl = "http://localhost:8000/v1";
        private String apiKey = "none";
        private Duration timeout = Duration.ofSeconds(30);
        /** Повторы SDK при сетевых ошибках и 5xx. */
        private int maxRetries = 2;
        /** Передавать JSON-схему как response_format (если сервер поддерживает, например vLLM); иначе — инструкцией в промпте. */
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

    /**
     * GigaChat API напрямую. Для отладки — публичный API с ключом авторизации физлица
     * (scope GIGACHAT_API_PERS), в контуре — свой адрес и ключ или клиентский сертификат.
     */
    public static class GigaChat {
        private String baseUrl = "https://api.giga.chat/v1/";
        private String authUrl = "https://ngw.devices.sberbank.ru:9443/api/v2/oauth";
        /** GIGACHAT_API_PERS | GIGACHAT_API_B2B | GIGACHAT_API_CORP */
        private String scope = "GIGACHAT_API_PERS";
        /** Ключ авторизации из личного кабинета (Base64 от client_id:client_secret). */
        private String credentials;
        /** Корневой сертификат Минцифры (PEM, путь или file:/classpath:); без него JVM не доверяет GigaChat. */
        private String caCerts;
        /** Клиентский сертификат и ключ (PEM) — вместо ключа авторизации, если так выдан доступ. */
        private String certificate;
        private String privateKey;
        /** Отключает проверку сертификатов сервера. Только для локальной отладки. */
        private boolean unsafeSsl = false;
        private Duration connectTimeout = Duration.ofSeconds(15);
        private Duration readTimeout = Duration.ofSeconds(60);
        /** Повторы при сетевых ошибках, 5xx и 429 (пауза 0.5, 1, 2 с…); повтор при ответе не по схеме делает шлюз. */
        private int maxRetries = 3;
        /** Одновременных запросов к API, не больше; 0 — без ограничения. У ключа физлица — 1. */
        private int maxConcurrency = 1;
                /** Температура этапов; null — значение модели по умолчанию. */
        private Double temperature;

        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getAuthUrl() { return authUrl; }
        public void setAuthUrl(String authUrl) { this.authUrl = authUrl; }
        public String getScope() { return scope; }
        public void setScope(String scope) { this.scope = scope; }
        public String getCredentials() { return credentials; }
        public void setCredentials(String credentials) { this.credentials = credentials; }
        public String getCaCerts() { return caCerts; }
        public void setCaCerts(String caCerts) { this.caCerts = caCerts; }
        public String getCertificate() { return certificate; }
        public void setCertificate(String certificate) { this.certificate = certificate; }
        public String getPrivateKey() { return privateKey; }
        public void setPrivateKey(String privateKey) { this.privateKey = privateKey; }
        public boolean isUnsafeSsl() { return unsafeSsl; }
        public void setUnsafeSsl(boolean unsafeSsl) { this.unsafeSsl = unsafeSsl; }
        public Duration getConnectTimeout() { return connectTimeout; }
        public void setConnectTimeout(Duration connectTimeout) { this.connectTimeout = connectTimeout; }
        public Duration getReadTimeout() { return readTimeout; }
        public void setReadTimeout(Duration readTimeout) { this.readTimeout = readTimeout; }
        public int getMaxRetries() { return maxRetries; }
        public void setMaxRetries(int maxRetries) { this.maxRetries = maxRetries; }
        public int getMaxConcurrency() { return maxConcurrency; }
        public void setMaxConcurrency(int maxConcurrency) { this.maxConcurrency = maxConcurrency; }
        public Double getTemperature() { return temperature; }
        public void setTemperature(Double temperature) { this.temperature = temperature; }
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
        /** gigachat | hashing | internal */
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
    public GigaChat getGigachat() { return gigachat; }
    public void setGigachat(GigaChat gigachat) { this.gigachat = gigachat; }
    public Internal getInternal() { return internal; }
    public void setInternal(Internal internal) { this.internal = internal; }
    public Recording getRecording() { return recording; }
    public void setRecording(Recording recording) { this.recording = recording; }
    public Masking getMasking() { return masking; }
    public void setMasking(Masking masking) { this.masking = masking; }
    public Embedding getEmbedding() { return embedding; }
    public void setEmbedding(Embedding embedding) { this.embedding = embedding; }
}
