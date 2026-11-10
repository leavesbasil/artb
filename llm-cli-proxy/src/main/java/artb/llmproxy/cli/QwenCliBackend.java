package artb.llmproxy.cli;

import java.util.ArrayList;
import java.util.List;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Qwen Code в headless-режиме ({@code qwen -p <prompt> -o json}). Своего системного промпта и
 * JSON-схемы у CLI нет: системные инструкции и схема склеиваются с запросом, а из ответа
 * вырезается JSON. Инструменты агента отключаются в настройках самого qwen, без {@code --yolo}.
 * Формат {@code -o json} отличается между версиями, поэтому разбор понимает оба известных варианта.
 */
public class QwenCliBackend implements CliBackend {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final String executable;

    public QwenCliBackend(String executable) {
        this.executable = executable;
    }

    @Override
    public String name() {
        return "qwen";
    }

    @Override
    public boolean nativeSchema() {
        return false;
    }

    @Override
    public List<String> command(CliCall call) {
        List<String> cmd = new ArrayList<>(List.of(executable, "-o", "json"));
        if (call.model() != null) {
            cmd.addAll(List.of("-m", call.model()));
        }
        StringBuilder prompt = new StringBuilder();
        if (call.system() != null && !call.system().isBlank()) {
            prompt.append("Инструкции:\n").append(call.system()).append("\n\nЗапрос:\n");
        }
        prompt.append(call.user());
        if (call.hasSchema()) {
            prompt.append("\n\nОтветь только JSON-объектом без пояснений и без markdown, строго по JSON-схеме:\n")
                    .append(call.jsonSchema());
        }
        cmd.addAll(List.of("-p", prompt.toString()));
        return cmd;
    }

    @Override
    public String stdin(CliCall call) {
        return "";
    }

    @Override
    public CliResult parse(String stdout) {
        return parseOutput(stdout);
    }

    public static CliResult parseOutput(String stdout) {
        JsonNode root;
        try {
            root = JSON.readTree(stdout);
        } catch (RuntimeException e) {
            return CliResult.failed("вывод не JSON");
        }
        if (root.isObject()) {
            if (root.hasNonNull("error")) {
                return CliResult.failed(root.get("error").toString());
            }
            if (root.hasNonNull("response")) {
                return CliResult.ok(root.get("response").asString());
            }
            if (root.hasNonNull("result")) {
                return CliResult.ok(root.get("result").asString());
            }
            return CliResult.failed("неизвестный формат объекта");
        }
        if (root.isArray()) {
            for (JsonNode node : root) {
                if ("result".equals(node.path("type").asString(""))) {
                    if (node.path("is_error").asBoolean(false)) {
                        return CliResult.failed(node.path("result").asString("error"));
                    }
                    return CliResult.ok(node.path("result").asString());
                }
            }
            return CliResult.failed("в массиве нет элемента type=result");
        }
        return CliResult.failed("неизвестный формат");
    }

    /** Вырезает JSON из ответа модели без нативной схемы: убирает markdown-ограждение и текст вокруг. */
    public static String extractJson(String text) {
        String t = text.strip();
        if (t.startsWith("```")) {
            int firstNewline = t.indexOf('\n');
            int lastFence = t.lastIndexOf("```");
            if (firstNewline > 0 && lastFence > firstNewline) {
                t = t.substring(firstNewline + 1, lastFence).strip();
            }
        }
        int start = t.indexOf('{');
        int end = t.lastIndexOf('}');
        return start >= 0 && end > start ? t.substring(start, end + 1) : t;
    }
}
