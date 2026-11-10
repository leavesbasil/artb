package artb.llmproxy.cli;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Claude Code в headless-режиме ({@code claude -p --output-format json}). Системный промпт заменяет
 * промпт агента; инструменты, настройки пользователя, MCP и сохранение сессий отключены.
 * С JSON-схемой ответ приходит в поле {@code structured_output}; схема работает через внутренний
 * инструмент, поэтому нужно {@code --max-turns 2}.
 */
public class ClaudeCliBackend implements CliBackend {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final String executable;
    private final double maxBudgetUsd;

    public ClaudeCliBackend(String executable, double maxBudgetUsd) {
        this.executable = executable;
        this.maxBudgetUsd = maxBudgetUsd;
    }

    @Override
    public String name() {
        return "claude";
    }

    @Override
    public boolean nativeSchema() {
        return true;
    }

    @Override
    public List<String> command(CliCall call) {
        List<String> cmd = new ArrayList<>(List.of(executable, "-p",
                "--output-format", "json",
                "--tools", "",
                "--setting-sources", "",
                "--strict-mcp-config",
                "--disable-slash-commands",
                "--no-session-persistence",
                "--max-turns", "2",
                "--max-budget-usd", String.format(Locale.ROOT, "%.2f", maxBudgetUsd)));
        if (call.model() != null) {
            cmd.addAll(List.of("--model", call.model()));
        }
        if (call.system() != null && !call.system().isBlank()) {
            cmd.addAll(List.of("--system-prompt", call.system()));
        }
        if (call.hasSchema()) {
            cmd.addAll(List.of("--json-schema", cliSchema(call.jsonSchema())));
        }
        return cmd;
    }

    @Override
    public String stdin(CliCall call) {
        return call.user();
    }

    @Override
    public CliResult parse(String stdout) {
        return parseOutput(stdout);
    }

    /**
     * Валидатор схем в Claude CLI не знает мета-схему draft 2020-12 ({@code "$schema"}), которую
     * проставляют генераторы схем, и отклоняет такую схему. Ключи {@code $schema} и {@code $id} убираются.
     */
    static String cliSchema(String schema) {
        JsonNode node = JSON.readTree(schema);
        if (node instanceof ObjectNode obj) {
            obj.remove("$schema");
            obj.remove("$id");
            return JSON.writeValueAsString(obj);
        }
        return schema;
    }

    public static CliResult parseOutput(String stdout) {
        JsonNode root;
        try {
            root = JSON.readTree(stdout);
        } catch (RuntimeException e) {
            return CliResult.failed("вывод не JSON: " + abbreviate(stdout));
        }
        if (root.path("is_error").asBoolean(false)) {
            return CliResult.failed(root.path("subtype").asString("error") + ": " + root.path("result").asString(""));
        }
        Double cost = root.hasNonNull("total_cost_usd") ? root.get("total_cost_usd").asDouble() : null;
        JsonNode usage = root.path("usage");
        long in = usage.path("input_tokens").asLong(0) + usage.path("cache_creation_input_tokens").asLong(0)
                + usage.path("cache_read_input_tokens").asLong(0);
        long out = usage.path("output_tokens").asLong(0);
        JsonNode structured = root.get("structured_output");
        if (structured != null && !structured.isNull()) {
            return CliResult.ok(JSON.writeValueAsString(structured), cost, in, out);
        }
        JsonNode result = root.get("result");
        if (result == null || result.isNull()) {
            return CliResult.failed("в выводе нет поля result");
        }
        return CliResult.ok(result.asString(), cost, in, out);
    }

    private static String abbreviate(String s) {
        return s.length() > 300 ? s.substring(0, 300) + "…" : s;
    }
}
