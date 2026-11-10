package artb.llmproxy.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

/** Контрактные тесты разбора вывода CLI на реальных образцах. */
class CliOutputParsingTest {

    @Test
    void claudeStructuredOutputIsReturnedAsJson() throws IOException {
        CliResult r = ClaudeCliBackend.parseOutput(resource("claude-structured.json"));
        assertThat(r.isError()).isFalse();
        assertThat(r.text()).contains("\"commercialIntent\"").contains("кредит на ремонт кухни");
        assertThat(r.costUsd()).isPositive();
    }

    @Test
    void claudeErrorIsReported() throws IOException {
        CliResult r = ClaudeCliBackend.parseOutput(resource("claude-error.json"));
        assertThat(r.isError()).isTrue();
        assertThat(r.error()).contains("error_max_budget_usd");
    }

    @Test
    void claudeNonJsonIsAnError() {
        assertThat(ClaudeCliBackend.parseOutput("Error: not logged in").isError()).isTrue();
    }

    @Test
    void claudeSchemaDropsMetaSchemaKey() {
        String schema = "{\"$schema\":\"https://json-schema.org/draft/2020-12/schema\",\"type\":\"object\","
                + "\"properties\":{\"a\":{\"type\":\"string\"}}}";
        assertThat(ClaudeCliBackend.cliSchema(schema)).doesNotContain("$schema").contains("\"properties\"");
    }

    @Test
    void claudeCommandIsIsolated() {
        var cmd = new ClaudeCliBackend("claude", 0.5).command(new CliCall("сис", "вопрос", "haiku", "{\"type\":\"object\"}"));
        assertThat(cmd).containsSequence("--model", "haiku").containsSequence("--system-prompt", "сис")
                .containsSequence("--tools", "").containsSequence("--max-turns", "2").contains("--json-schema");
    }

    @Test
    void qwenObjectFormat() throws IOException {
        assertThat(QwenCliBackend.parseOutput(resource("qwen-object.json")).text()).contains("кредит на ремонт");
    }

    @Test
    void qwenArrayFormat() throws IOException {
        assertThat(QwenCliBackend.parseOutput(resource("qwen-array.json")).text()).contains("кредит на ремонт");
    }

    @Test
    void qwenSchemaGoesIntoPromptAndJsonIsExtracted() {
        var cmd = new QwenCliBackend("qwen").command(new CliCall("сис", "вопрос", null, "{\"type\":\"object\"}"));
        assertThat(cmd.getLast()).contains("Инструкции:").contains("вопрос").contains("JSON-схеме");
        assertThat(QwenCliBackend.extractJson("Вот ответ:\n```json\n{\"a\": 1}\n```")).isEqualTo("{\"a\": 1}");
    }

    private String resource(String name) throws IOException {
        try (var in = getClass().getResourceAsStream("/cli/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
