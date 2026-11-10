package artb.llmproxy.api;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import artb.llmproxy.ModelRouter;
import artb.llmproxy.ProxyProperties;
import artb.llmproxy.cli.CliException;
import tools.jackson.databind.JsonNode;

/** Подмножество OpenAI API, которого хватает Spring AI и любому OpenAI-совместимому клиенту. */
@RestController
public class OpenAiController {

    private final ChatCompletionService completions;
    private final ModelRouter router;
    private final ProxyProperties props;

    public OpenAiController(ChatCompletionService completions, ModelRouter router, ProxyProperties props) {
        this.completions = completions;
        this.router = router;
        this.props = props;
    }

    @PostMapping("/v1/chat/completions")
    public ResponseEntity<Map<String, Object>> chat(@RequestBody JsonNode request,
            @RequestHeader(value = "Authorization", required = false) String auth) {
        checkAuth(auth);
        return ResponseEntity.ok(completions.complete(request));
    }

    @GetMapping("/v1/models")
    public Map<String, Object> models(@RequestHeader(value = "Authorization", required = false) String auth) {
        checkAuth(auth);
        List<Map<String, Object>> data = router.listed().stream()
                .map(id -> Map.<String, Object>of("id", id, "object", "model", "created", 0, "owned_by", "llm-cli-proxy"))
                .toList();
        return Map.of("object", "list", "data", data);
    }

    private void checkAuth(String header) {
        String key = props.getApiKey();
        if (key != null && !key.isBlank() && !("Bearer " + key).equals(header)) {
            throw new Unauthorized();
        }
    }

    static class Unauthorized extends RuntimeException {}

    @ExceptionHandler(Unauthorized.class)
    ResponseEntity<Map<String, Object>> unauthorized() {
        return error(HttpStatus.UNAUTHORIZED, "invalid_api_key", "неверный API-ключ");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, Object>> badRequest(IllegalArgumentException e) {
        return error(HttpStatus.BAD_REQUEST, "invalid_request_error", e.getMessage());
    }

    @ExceptionHandler(CliException.Timeout.class)
    ResponseEntity<Map<String, Object>> timeout(CliException.Timeout e) {
        return error(HttpStatus.GATEWAY_TIMEOUT, "timeout", e.getMessage());
    }

    @ExceptionHandler(CliException.class)
    ResponseEntity<Map<String, Object>> cliFailed(CliException e) {
        return error(HttpStatus.BAD_GATEWAY, "server_error", e.getMessage());
    }

    private static ResponseEntity<Map<String, Object>> error(HttpStatus status, String type, String message) {
        Map<String, Object> err = new LinkedHashMap<>();
        err.put("message", message);
        err.put("type", type);
        err.put("code", null);
        return ResponseEntity.status(status).body(Map.of("error", err));
    }
}
