package artb.llmproxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import artb.llmproxy.cli.ClaudeCliBackend;
import artb.llmproxy.cli.QwenCliBackend;

class ModelRouterTest {

    private final ModelRouter router = new ModelRouter(
            List.of(new ClaudeCliBackend("claude", 0.5), new QwenCliBackend("qwen")),
            Map.of("strong", "claude/sonnet"));

    @Test
    void resolvesBackendModelAndAliases() {
        assertThat(router.resolve("claude/haiku")).satisfies(r -> {
            assertThat(r.backend().name()).isEqualTo("claude");
            assertThat(r.cliModel()).isEqualTo("haiku");
        });
        assertThat(router.resolve("qwen").cliModel()).isNull();
        assertThat(router.resolve("strong").cliModel()).isEqualTo("sonnet");
    }

    @Test
    void unknownModelIsRejected() {
        assertThatThrownBy(() -> router.resolve("gpt-4o")).isInstanceOf(IllegalArgumentException.class);
    }
}
