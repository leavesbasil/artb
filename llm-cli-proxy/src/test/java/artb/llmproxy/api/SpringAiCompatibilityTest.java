package artb.llmproxy.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import artb.llmproxy.cli.CliBackend;
import artb.llmproxy.cli.CliCall;
import artb.llmproxy.cli.CliResult;
import artb.llmproxy.cli.CliRunner;

/**
 * Настоящий клиент Spring AI OpenAI против прокси, CLI заменён фейком: формат ответа понятен
 * официальному SDK, а JSON-схема из {@code outputSchema} доходит до CLI.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "server.address=127.0.0.1")
class SpringAiCompatibilityTest {

    static final List<CliCall> CALLS = new CopyOnWriteArrayList<>();

    @TestConfiguration
    static class FakeCli {
        @Bean
        @Primary
        CliRunner fakeRunner() {
            return new CliRunner(Duration.ofSeconds(1), 1) {
                @Override
                public CliResult run(CliBackend backend, CliCall call) {
                    CALLS.add(call);
                    String text = call.hasSchema() ? "{\"answer\":\"ok\"}" : backend.name() + ":" + call.model() + " ответил";
                    return CliResult.ok(text, 0.001, 10, 5);
                }
            };
        }
    }

    @LocalServerPort
    int port;

    @Autowired
    CliRunner runner;

    @BeforeEach
    void reset() {
        CALLS.clear();
    }

    @Test
    void plainChatThroughOfficialClient() {
        ChatResponse r = client("claude/haiku").call(new Prompt(List.of(new SystemMessage("сис"), new UserMessage("привет"))));

        assertThat(r.getResult().getOutput().getText()).isEqualTo("claude:haiku ответил");
        assertThat(CALLS).singleElement().satisfies(c -> {
            assertThat(c.system()).isEqualTo("сис");
            assertThat(c.user()).isEqualTo("привет");
            assertThat(c.jsonSchema()).isNull();
        });
    }

    @Test
    void outputSchemaReachesCli() {
        String schema = "{\"type\":\"object\",\"properties\":{\"answer\":{\"type\":\"string\"}},\"required\":[\"answer\"]}";
        // Опции вызова без явной модели подставляют модель по умолчанию Spring AI (gpt-5-mini),
        // поэтому модель повторяется рядом со схемой.
        ChatResponse r = client("strong").call(new Prompt(List.of(new UserMessage("вопрос")),
                OpenAiChatOptions.builder().model("strong").outputSchema(schema).build()));

        assertThat(r.getResult().getOutput().getText()).isEqualTo("{\"answer\":\"ok\"}");
        assertThat(CALLS).singleElement().satisfies(c -> {
            assertThat(c.model()).isEqualTo("sonnet");
            assertThat(c.jsonSchema()).contains("\"answer\"");
        });
    }

    @Test
    void unknownModelIsAClientError() {
        assertThatThrownBy(() -> client("gpt-4o").call(new Prompt("привет"))).hasMessageContaining("неизвестная модель");
    }

    private OpenAiChatModel client(String model) {
        return OpenAiChatModel.builder()
                .options(OpenAiChatOptions.builder()
                        .baseUrl("http://127.0.0.1:" + port + "/v1")
                        .apiKey("none")
                        .model(model)
                        .maxRetries(0)
                        .build())
                .build();
    }
}
