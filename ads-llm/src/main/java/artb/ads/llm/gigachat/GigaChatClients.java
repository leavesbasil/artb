package artb.ads.llm.gigachat;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.TrustManagerFactory;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.web.client.ResourceAccessException;

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
        GigaChatAuthProperties auth = GigaChatAuthProperties.builder()
                .scope(GigaChatApiScope.valueOf(props.getScope()))
                .unsafeSsl(props.isUnsafeSsl())
                .bearer(certAuth ? new GigaChatAuthProperties.Bearer() : GigaChatAuthProperties.Bearer.builder()
                        .url(props.getAuthUrl())
                        .apiKey(props.getCredentials())
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
        return new GigaChatApi(apiProps, certAuth ? keyManagers(certificate, privateKey) : null, trustManagers(caCerts));
    }

    private GigaChatInternalProperties internalProperties() {
        GigaChatInternalProperties internal = new GigaChatInternalProperties();
        internal.setConnectTimeout(props.getConnectTimeout());
        internal.setReadTimeout(props.getReadTimeout());
        return internal;
    }

    /** Пусто — не задано; путь без префикса — файл; иначе как в Spring: classpath:, file:. */
    private static Resource resource(String location) {
        if (location == null || location.isBlank()) {
            return null;
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

    /** Повторяем только то, что может пройти со второго раза: сетевые сбои и 5xx. */
    private RetryTemplate retryTemplate() {
        return new RetryTemplate(RetryPolicy.builder()
                .maxRetries(props.getMaxRetries())
                .delay(java.time.Duration.ofMillis(500))
                .predicate(e -> e instanceof TransientAiException || e instanceof ResourceAccessException)
                .build());
    }
}
