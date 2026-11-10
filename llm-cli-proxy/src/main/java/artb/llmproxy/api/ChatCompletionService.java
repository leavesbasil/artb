package artb.llmproxy.api;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import artb.llmproxy.ModelRouter;
import artb.llmproxy.cli.CliCall;
import artb.llmproxy.cli.CliException;
import artb.llmproxy.cli.CliResult;
import artb.llmproxy.cli.CliRunner;
import artb.llmproxy.cli.QwenCliBackend;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** {@code POST /v1/chat/completions} → вызов CLI → ответ в формате OpenAI. */
public class ChatCompletionService {

    private static final Logger log = LoggerFactory.getLogger(ChatCompletionService.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final ModelRouter router;
    private final CliRunner runner;

    public ChatCompletionService(ModelRouter router, CliRunner runner) {
        this.router = router;
        this.runner = runner;
    }

    public Map<String, Object> complete(JsonNode request) {
        if (request.path("stream").asBoolean(false)) {
            throw new IllegalArgumentException("stream не поддерживается: CLI отдаёт ответ целиком");
        }
        JsonNode tools = request.get("tools");
        if (tools != null && tools.isArray() && !tools.isEmpty()) {
            throw new IllegalArgumentException("tools не поддерживаются: инструменты CLI отключены");
        }
        String requestedModel = request.path("model").asString(null);
        ModelRouter.Route route = router.resolve(requestedModel);
        PromptRenderer.Rendered prompt = PromptRenderer.render(request.get("messages"));
        String schema = schema(request.get("response_format"));
        boolean jsonObject = "json_object".equals(request.path("response_format").path("type").asString(""));
        String user = jsonObject && schema == null
                ? prompt.user() + "\n\nОтветь только JSON-объектом без пояснений и без markdown."
                : prompt.user();

        long started = System.nanoTime();
        CliResult result = runner.run(route.backend(), new CliCall(prompt.system(), user, route.cliModel(), schema));
        long ms = (System.nanoTime() - started) / 1_000_000;
        if (result.isError()) {
            throw new CliException(route.backend().name() + ": " + result.error());
        }
        String text = (schema != null || jsonObject) && !route.backend().nativeSchema()
                ? QwenCliBackend.extractJson(result.text())
                : result.text();
        log.info("{} → {}/{}: схема={}, {} мс, стоимость {}", requestedModel, route.backend().name(),
                route.cliModel() == null ? "default" : route.cliModel(), schema != null, ms, result.costUsd());
        return response(requestedModel, text, result);
    }

    /** JSON-схема из {@code response_format: {type: json_schema, json_schema: {schema: …}}}. */
    static String schema(JsonNode responseFormat) {
        if (responseFormat == null || !"json_schema".equals(responseFormat.path("type").asString(""))) {
            return null;
        }
        JsonNode schema = responseFormat.path("json_schema").get("schema");
        if (schema == null || schema.isNull()) {
            throw new IllegalArgumentException("response_format.json_schema.schema не задан");
        }
        return JSON.writeValueAsString(schema);
    }

    private static Map<String, Object> response(String model, String text, CliResult result) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "assistant");
        message.put("content", text);
        message.put("refusal", null);
        message.put("annotations", List.of());

        Map<String, Object> choice = new LinkedHashMap<>();
        choice.put("index", 0);
        choice.put("message", message);
        choice.put("logprobs", null);
        choice.put("finish_reason", "stop");

        Map<String, Object> usage = new LinkedHashMap<>();
        usage.put("prompt_tokens", result.inputTokens());
        usage.put("completion_tokens", result.outputTokens());
        usage.put("total_tokens", result.inputTokens() + result.outputTokens());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", "chatcmpl-" + UUID.randomUUID());
        body.put("object", "chat.completion");
        body.put("created", Instant.now().getEpochSecond());
        body.put("model", model);
        body.put("choices", List.of(choice));
        body.put("usage", usage);
        body.put("system_fingerprint", null);
        return body;
    }
}
