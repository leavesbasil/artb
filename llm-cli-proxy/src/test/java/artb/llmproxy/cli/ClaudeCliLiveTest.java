package artb.llmproxy.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Живой вызов Claude CLI. Запуск: ./gradlew :llm-cli-proxy:test -PllmCli */
@Tag("llm-cli")
class ClaudeCliLiveTest {

    @Test
    void returnsJsonBySchema() {
        String schema = """
                {"$schema":"https://json-schema.org/draft/2020-12/schema","type":"object",
                 "properties":{"commercialIntent":{"type":"number"},"queries":{"type":"array","items":{"type":"string"}}},
                 "required":["commercialIntent","queries"]}""";
        CliResult r = new CliRunner(Duration.ofSeconds(60), 1).run(new ClaudeCliBackend("claude", 0.5),
                new CliCall("Ты модуль выделения коммерческого интента банка. Отвечай по схеме.",
                        "Клиент: хочу сделать ремонт кухни, своих денег не хватает", "haiku", schema));
        assertThat(r.isError()).isFalse();
        assertThat(r.text()).contains("commercialIntent");
    }
}
