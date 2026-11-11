package artb.ads.llm.gigachat;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.TrustManagerFactory;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.retry.RetryUtils;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.util.StreamUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.ResponseErrorHandler;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

import artb.ads.llm.LlmProperties;
import chat.giga.springai.GigaChatEmbeddingModel;
import chat.giga.springai.GigaChatEmbeddingOptions;
import chat.giga.springai.GigaChatModel;
import chat.giga.springai.GigaChatOptions;
import chat.giga.springai.api.GigaChatApiProperties;
import chat.giga.springai.api.GigaChatInternalProperties;
import chat.giga.springai.api.auth.GigaChatApiScope;
import chat.giga.springai.api.auth.GigaChatAuthProperties;
import chat.giga.springai.api.chat.GigaChatApi;
import io.micrometer.observation.ObservationRegistry;
import nl.altindag.ssl.pem.util.PemUtils;
import nl.altindag.ssl.util.KeyManagerUtils;
import nl.altindag.ssl.util.TrustManagerUtils;

/**
 * Клиенты GigaChat API на библиотеке spring-ai-gigachat. Один {@link GigaChatApi} на приложение:
 * он получает OAuth-токен по ключу авторизации и обновляет его до истечения (токен живёт 30 минут).
 * Клиент создаётся при первом вызове, поэтому профили без GigaChat не требуют ключа.
 */
public class GigaChatClients {

    private final LlmProperties.GigaChat props;
    private volatile GigaChatApi api;

    public GigaChatClients(LlmProperties.GigaChat props) {
        this.props = props;
    }

    /** Модель чата. JSON-схему GigaChat API не принимает — шлюз передаёт её инструкцией в промпте. */
    public ChatModel chatModel(String model) {
        if (model == null) {
            throw new IllegalArgumentException("Для провайдера gigachat нужно имя модели, например GigaChat-2-Max");
        }
        GigaChatInternalProperties internal = internalProperties();
        return GigaChatModel.builder()
                .gigaChatApi(api())
                .defaultOptions(GigaChatOptions.builder()
                        .model(model)
                        .temperature(props.getTemperature())
                        .build())
                .retryTemplate(retryTemplate())
                .internalProperties(internal)
                .build();
    }

    public EmbeddingModel embeddingModel(String model) {
        return new GigaChatEmbeddingModel(api(),
                GigaChatEmbeddingOptions.builder().withModel(model).build(),
                retryTemplate(), ObservationRegistry.NOOP);
    }

    GigaChatApi api() {
        GigaChatApi current = api;
        if (current == null) {
            synchronized (this) {
                current = api;
                if (current == null) {
                    current = api = createApi();
                }
            }
        }
        return current;
    }

    private GigaChatApi createApi() {
        Resource certificate = resource(props.getCertificate());
        Resource privateKey = resource(props.getPrivateKey());
        Resource caCerts = resource(props.getCaCerts());
        boolean certAuth = certificate != null && privateKey != null;
        if (!certAuth && (props.getCredentials() == null || props.getCredentials().isBlank())) {
            throw new IllegalStateException("GigaChat: не задан ключ авторизации. Укажите переменную GIGACHAT_CREDENTIALS "
                    + "(ads.llm.gigachat.credentials) или клиентский сертификат (certificate и private-key)");
        }
        String credentials = certAuth ? null : normalizeCredentials(props.getCredentials());
        GigaChatAuthProperties auth = GigaChatAuthProperties.builder()
                .scope(GigaChatApiScope.valueOf(props.getScope()))
                .unsafeSsl(props.isUnsafeSsl())
                .bearer(certAuth ? new GigaChatAuthProperties.Bearer() : GigaChatAuthProperties.Bearer.builder()
                        .url(props.getAuthUrl())
                        .apiKey(credentials)
                        .build())
                .certs(GigaChatAuthProperties.Certificates.builder()
                        .certificate(certificate)
                        .privateKey(privateKey)
                        .caCerts(caCerts)
                        .build())
                .build();
        GigaChatApiProperties apiProps = GigaChatApiProperties.builder()
                .baseUrl(props.getBaseUrl())
                .auth(auth)
                .internal(internalProperties())
                .build();
        RestClient.Builder rest = RestClient.builder();
        if (props.getMaxConcurrency() > 0) {
            rest.requestInterceptor(concurrencyLimit(props.getMaxConcurrency(), props.getAuthUrl()));
        }
        return new GigaChatApi(apiProps, rest, WebClient.builder(), RATE_LIMIT_AWARE_ERRORS,
                certAuth ? keyManagers(certificate, privateKey) : null, trustManagers(caCerts));
    }

    private GigaChatInternalProperties internalProperties() {
        GigaChatInternalProperties internal = new GigaChatInternalProperties();
        internal.setConnectTimeout(props.getConnectTimeout());
        internal.setReadTimeout(props.getReadTimeout());
        return internal;
    }

    /**
     * Ключ авторизации — Base64 от {@code client_id:client_secret}. Обрамляющие кавычки (как принято
     * в .env) и префикс {@code Basic} снимаются. Вставленный {@code client_id:ключ} или сырой
     * {@code client_id:client_secret} OAuth отклоняет невнятным 400, поэтому проверяем заранее;
     * значение ключа в сообщение не попадает.
     */
    static String normalizeCredentials(String credentials) {
        String hint = "GigaChat: GIGACHAT_CREDENTIALS должен быть ключом авторизации из личного кабинета — "
                + "Base64 от client_id:client_secret";
        String key = credentials.strip();
        if (key.length() >= 2 && (key.startsWith("\"") && key.endsWith("\"") || key.startsWith("'") && key.endsWith("'"))) {
            key = key.substring(1, key.length() - 1).strip();
        }
        if (key.regionMatches(true, 0, "Basic ", 0, 6)) {
            key = key.substring(6).strip();
        }
        if (key.contains(":")) {
            throw new IllegalStateException(hint + ". В значении есть двоеточие: похоже, вставлен client_id:ключ "
                    + "или client_id:client_secret — оставьте только ключ авторизации");
        }
        try {
            java.util.Base64.getDecoder().decode(key);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(hint + ". Значение не декодируется из Base64");
        }
        return key;
    }

    /** Пусто — не задано; путь без префикса — файл; иначе как в Spring: classpath:, file:. */
    private static Resource resource(String location) {
        if (location == null || location.isBlank()) {
            return null;
        }
        location = location.strip();
        if (location.length() >= 2 && (location.startsWith("\"") && location.endsWith("\"")
                || location.startsWith("'") && location.endsWith("'"))) {
            location = location.substring(1, location.length() - 1);
        }
        return location.contains(":") && !location.matches("^[A-Za-z]:[\\\\/].*")
                ? new DefaultResourceLoader().getResource(location)
                : new FileSystemResource(location);
    }

    private static KeyManagerFactory keyManagers(Resource certificate, Resource privateKey) {
        try (InputStream cert = certificate.getInputStream();
             InputStream key = privateKey.getInputStream()) {
            return KeyManagerUtils.createKeyManagerFactory(PemUtils.loadIdentityMaterial(cert, key));
        } catch (IOException e) {
            throw new UncheckedIOException("GigaChat: не удалось прочитать клиентский сертификат", e);
        }
    }

    private static TrustManagerFactory trustManagers(Resource ca) {
        if (ca == null) {
            return null;
        }
        try (InputStream in = ca.getInputStream()) {
            return TrustManagerUtils.createTrustManagerFactory(PemUtils.loadTrustMaterial(in));
        } catch (IOException e) {
            throw new UncheckedIOException("GigaChat: не удалось прочитать корневой сертификат " + ca, e);
        }
    }

    /**
     * Ошибки HTTP как в Spring AI, но 429 — повторяемая: у GigaChat жёсткий лимит одновременных
     * запросов (у ключа физлица — единицы), и параллельные вызовы агента и подбора в него упираются.
     */
    static final ResponseErrorHandler RATE_LIMIT_AWARE_ERRORS = new ResponseErrorHandler() {
        @Override
        public boolean hasError(org.springframework.http.client.ClientHttpResponse response) throws IOException {
            return response.getStatusCode().isError();
        }

        @Override
        public void handleError(java.net.URI url, org.springframework.http.HttpMethod method,
                org.springframework.http.client.ClientHttpResponse response) throws IOException {
            if (response.getStatusCode().value() == HttpStatus.TOO_MANY_REQUESTS.value()) {
                String body = StreamUtils.copyToString(response.getBody(), java.nio.charset.StandardCharsets.UTF_8);
                throw new TransientAiException("429 - " + body);
            }
            RetryUtils.DEFAULT_RESPONSE_ERROR_HANDLER.handleError(url, method, response);
        }
    };

    /**
     * Не больше {@code permits} одновременных запросов к API. У ключа физлица GigaChat пропускает
     * один запрос за раз, а демо зовёт агента и подбор параллельно — без очереди один из них
     * получает 429. Ограничение — на каждую HTTP-попытку, поэтому пауза между повторами слот не держит.
     * Запрос токена идёт мимо очереди: он выполняется внутри запроса, который слот уже занял.
     */
    static org.springframework.http.client.ClientHttpRequestInterceptor concurrencyLimit(int permits, String authUrl) {
        java.util.concurrent.Semaphore slots = new java.util.concurrent.Semaphore(permits, true);
        return (request, body, execution) -> {
            if (authUrl != null && request.getURI().toString().startsWith(authUrl)) {
                return execution.execute(request, body);
            }
            try {
                slots.acquire();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("GigaChat: прервано ожидание очереди запросов", e);
            }
            try {
                return execution.execute(request, body);
            } finally {
                slots.release();
            }
        };
    }

    /** Повторяем только то, что может пройти со второго раза: сетевые сбои, 5xx и 429, с нарастающей паузой. */
    private RetryTemplate retryTemplate() {
        return new RetryTemplate(RetryPolicy.builder()
                .maxRetries(props.getMaxRetries())
                .delay(java.time.Duration.ofMillis(500))
                .multiplier(2)
                .maxDelay(java.time.Duration.ofSeconds(4))
                .predicate(e -> e instanceof TransientAiException || e instanceof ResourceAccessException)
                .build());
    }
}
