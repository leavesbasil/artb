package artb.ads.llm.gigachat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import artb.ads.llm.DefaultLlmGateway;
import artb.ads.llm.LlmCall;
import artb.ads.llm.LlmProperties;
import artb.ads.llm.LlmProperties.StageModel;
import artb.ads.llm.LlmStage;
import artb.ads.llm.StageModels;
import artb.ads.llm.embedding.SpringAiTextEmbedder;
import artb.ads.llm.masking.RegexPiiMasker;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Провайдер gigachat против фейкового GigaChat API: OAuth, чат, эмбеддинги. */
class GigaChatClientsTest {

    record Intent(double commercialIntent, List<String> queries) {}

    private static final ObjectMapper JSON = new ObjectMapper();

    private HttpServer server;
    private final List<Map<String, String>> oauthRequests = new CopyOnWriteArrayList<>();
    private final List<JsonNode> chatRequests = new CopyOnWriteArrayList<>();
    private final List<JsonNode> embeddingRequests = new CopyOnWriteArrayList<>();
    private final Map<String, String> authHeaders = new ConcurrentHashMap<>();
    private final AtomicInteger chatCalls = new AtomicInteger();
    private final AtomicInteger rateLimited = new AtomicInteger();
    private final AtomicInteger activeChats = new AtomicInteger();
    private final AtomicInteger maxActiveChats = new AtomicInteger();
    private volatile long chatDelayMs;
    private volatile String chatAnswer = "{\"commercialIntent\": 0.7, \"queries\": [\"ипотека\"]}";

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/oauth", ex -> {
            oauthRequests.add(Map.of(
                    "authorization", ex.getRequestHeaders().getFirst("Authorization"),
                    "rquid", String.valueOf(ex.getRequestHeaders().getFirst("RqUID")),
                    "body", body(ex)));
            respond(ex, 200, "{\"access_token\":\"tok-1\",\"expires_at\":" + (System.currentTimeMillis() + 1_800_000) + "}");
        });
        server.createContext("/api/v1/chat/completions", ex -> {
            authHeaders.put("chat", ex.getRequestHeaders().getFirst("Authorization"));
            chatRequests.add(JSON.readTree(body(ex)));
            chatCalls.incrementAndGet();
            maxActiveChats.accumulateAndGet(activeChats.incrementAndGet(), Math::max);
            try {
                Thread.sleep(chatDelayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                activeChats.decrementAndGet();
            }
            if (rateLimited.getAndDecrement() > 0) {
                respond(ex, 429, "{\"status\":429,\"message\":\"Too Many Requests\"}");
                return;
            }
            respond(ex, 200, JSON.writeValueAsString(Map.of(
                    "choices", List.of(Map.of("index", 0, "finish_reason", "stop",
                            "message", Map.of("role", "assistant", "content", chatAnswer))),
                    "created", 1, "model", "GigaChat-2", "object", "chat.completion",
                    "usage", Map.of("prompt_tokens", 10, "completion_tokens", 5, "total_tokens", 15))));
        });
        server.createContext("/api/v1/embeddings", ex -> {
            authHeaders.put("embeddings", ex.getRequestHeaders().getFirst("Authorization"));
            JsonNode req = JSON.readTree(body(ex));
            embeddingRequests.add(req);
            var data = new java.util.ArrayList<Map<String, Object>>();
            for (int i = 0; i < req.get("input").size(); i++) {
                data.add(Map.of("object", "embedding", "index", i, "embedding", List.of(0.6, 0.8, (double) i),
                        "usage", Map.of("prompt_tokens", 3)));
            }
            respond(ex, 200, JSON.writeValueAsString(Map.of("object", "list", "model", "EmbeddingsGigaR", "data", data)));
        });
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void stageCallsGoToGigaChatWithOAuthTokenAndSchemaInPrompt() {
        LlmProperties props = props();
        props.setDefaultStage(new StageModel("gigachat", "GigaChat-2"));
        props.getStages().put("judge", new StageModel("gigachat", "GigaChat-2-Max"));
        GigaChatClients clients = new GigaChatClients(props.getGigachat());
        var gateway = new DefaultLlmGateway(new StageModels(props, clients), new RegexPiiMasker(), true);

        LlmCall<Intent> call = gateway.call(LlmStage.INTENT, "Ты классификатор", "мой телефон +7 916 123-45-67, хочу ипотеку",
                Intent.class);
        gateway.call(LlmStage.JUDGE, "Ты судья", "кандидаты", Intent.class);

        assertThat(call.value().commercialIntent()).isEqualTo(0.7);
        assertThat(call.model()).isEqualTo("gigachat:GigaChat-2");

        // OAuth: Basic с ключом авторизации, scope в теле, RqUID; токен получен один раз на оба вызова.
        assertThat(oauthRequests).hasSize(1);
        assertThat(oauthRequests.getFirst().get("authorization")).isEqualTo("Basic dGVzdDprZXk=");
        assertThat(oauthRequests.getFirst().get("body")).isEqualTo("scope=GIGACHAT_API_PERS");
        assertThat(oauthRequests.getFirst().get("rquid")).isNotBlank().isNotEqualTo("null");
        assertThat(authHeaders.get("chat")).isEqualTo("Bearer tok-1");

        // Модель этапа уходит в запросе; схема — инструкцией в промпте, персональные данные замаскированы.
        assertThat(chatRequests).extracting(r -> r.get("model").asString()).containsExactly("GigaChat-2", "GigaChat-2-Max");
        String userText = lastUserMessage(chatRequests.getFirst());
        assertThat(userText).contains("[ТЕЛЕФОН]").doesNotContain("916").contains("commercialIntent");
        assertThat(chatRequests.getFirst().has("response_format")).isFalse();
    }

    @Test
    void rateLimitIsRetriedWithBackoff() {
        rateLimited.set(2);
        LlmProperties props = props();
        props.getGigachat().setMaxRetries(3);
        props.setDefaultStage(new StageModel("gigachat", "GigaChat-2"));
        var gateway = new DefaultLlmGateway(new StageModels(props, new GigaChatClients(props.getGigachat())),
                new RegexPiiMasker(), true);

        LlmCall<Intent> call = gateway.call(LlmStage.GATE, "s", "u", Intent.class);

        assertThat(call.value().commercialIntent()).isEqualTo(0.7);
        assertThat(chatCalls.get()).isEqualTo(3);
    }

    @Test
    void concurrentCallsAreQueuedToConfiguredLimit() throws Exception {
        chatDelayMs = 150;
        LlmProperties props = props();
        props.getGigachat().setMaxConcurrency(1);
        props.setDefaultStage(new StageModel("gigachat", "GigaChat-2"));
        var gateway = new DefaultLlmGateway(new StageModels(props, new GigaChatClients(props.getGigachat())),
                new RegexPiiMasker(), true);

        try (var pool = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var calls = java.util.stream.IntStream.range(0, 4)
                    .mapToObj(i -> pool.submit(() -> gateway.call(LlmStage.GATE, "s", "u" + i, Intent.class)))
                    .toList();
            for (var c : calls) {
                assertThat(c.get().value().commercialIntent()).isEqualTo(0.7);
            }
        }

        assertThat(chatCalls.get()).isEqualTo(4);
        assertThat(maxActiveChats.get()).isEqualTo(1);
    }

    @Test
    void invalidJsonIsRetriedOnceByGateway() {
        chatAnswer = "не JSON";
        LlmProperties props = props();
        props.setDefaultStage(new StageModel("gigachat", "GigaChat-2"));
        var gateway = new DefaultLlmGateway(new StageModels(props, new GigaChatClients(props.getGigachat())),
                new RegexPiiMasker(), true);

        assertThatThrownBy(() -> gateway.call(LlmStage.GATE, "s", "u", Intent.class)).isInstanceOf(RuntimeException.class);
        assertThat(chatCalls.get()).isEqualTo(2);
    }

    @Test
    void embeddingsUseConfiguredModel() {
        LlmProperties props = props();
        var embedder = new SpringAiTextEmbedder(new GigaChatClients(props.getGigachat()).embeddingModel("EmbeddingsGigaR"),
                "gigachat:EmbeddingsGigaR");

        List<float[]> vectors = embedder.embedAll(List.of("хочу ипотеку", "открыть вклад"));

        assertThat(vectors).hasSize(2);
        assertThat(vectors.get(1)).containsExactly(0.6f, 0.8f, 1.0f);
        assertThat(embeddingRequests.getFirst().get("model").asString()).isEqualTo("EmbeddingsGigaR");
        assertThat(authHeaders.get("embeddings")).isEqualTo("Bearer tok-1");
    }

    @Test
    void missingCredentialsFailWithClearMessage() {
        LlmProperties props = props();
        props.getGigachat().setCredentials("");
        GigaChatClients clients = new GigaChatClients(props.getGigachat());

        assertThatThrownBy(() -> clients.chatModel("GigaChat-2"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("GIGACHAT_CREDENTIALS");
    }

    @Test
    void credentialsAreNormalizedAndMalformedOnesRejectedBeforeOAuth() {
        assertThat(GigaChatClients.normalizeCredentials("\"ZGF0YQ==\"")).isEqualTo("ZGF0YQ==");
        assertThat(GigaChatClients.normalizeCredentials(" 'ZGF0YQ==' ")).isEqualTo("ZGF0YQ==");
        assertThat(GigaChatClients.normalizeCredentials("Basic ZGF0YQ==")).isEqualTo("ZGF0YQ==");
        assertThatThrownBy(() -> GigaChatClients.normalizeCredentials("0f0e-id:ZGF0YQ=="))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("двоеточие")
                .hasMessageNotContaining("ZGF0YQ");
        assertThatThrownBy(() -> GigaChatClients.normalizeCredentials("не base64"))
                .hasMessageContaining("Base64");

        LlmProperties props = props();
        props.getGigachat().setCredentials("client:secret");
        assertThatThrownBy(() -> new GigaChatClients(props.getGigachat()).chatModel("GigaChat-2"))
                .hasMessageContaining("двоеточие");
        assertThat(oauthRequests).isEmpty();
    }

    @Test
    void quotedCredentialsReachOAuthWithoutQuotes() {
        LlmProperties props = props();
        props.getGigachat().setCredentials("\"dGVzdDprZXk=\"");
        props.setDefaultStage(new StageModel("gigachat", "GigaChat-2"));
        var gateway = new DefaultLlmGateway(new StageModels(props, new GigaChatClients(props.getGigachat())),
                new RegexPiiMasker(), true);

        gateway.call(LlmStage.GATE, "s", "u", Intent.class);

        assertThat(oauthRequests.getFirst().get("authorization")).isEqualTo("Basic dGVzdDprZXk=");
    }

    private LlmProperties props() {
        LlmProperties props = new LlmProperties();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        props.getGigachat().setBaseUrl(base + "/api/v1");
        props.getGigachat().setAuthUrl(base + "/oauth");
        props.getGigachat().setCredentials("dGVzdDprZXk=");
        props.getGigachat().setMaxRetries(0);
        return props;
    }

    private static String lastUserMessage(JsonNode request) {
        JsonNode messages = request.get("messages");
        for (int i = messages.size() - 1; i >= 0; i--) {
            if ("user".equals(messages.get(i).get("role").asString())) {
                return messages.get(i).get("content").asString();
            }
        }
        return "";
    }

    private static String body(HttpExchange ex) throws IOException {
        return new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    private static void respond(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }
}
