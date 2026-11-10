package artb.llmproxy;

import java.util.List;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import artb.llmproxy.api.ChatCompletionService;
import artb.llmproxy.cli.ClaudeCliBackend;
import artb.llmproxy.cli.CliBackend;
import artb.llmproxy.cli.CliRunner;
import artb.llmproxy.cli.QwenCliBackend;

/**
 * Локальный OpenAI-совместимый прокси к Claude Code CLI и Qwen Code CLI. Только для тестов и
 * отладки на синтетических данных: каждый вызов — отдельный процесс и секунды задержки.
 */
@SpringBootApplication
@EnableConfigurationProperties(ProxyProperties.class)
public class ProxyApplication {

    public static void main(String[] args) {
        SpringApplication.run(ProxyApplication.class, args);
    }

    @Bean
    CliRunner cliRunner(ProxyProperties props) {
        return new CliRunner(props.getTimeout(), props.getMaxConcurrency());
    }

    @Bean
    ModelRouter modelRouter(ProxyProperties props) {
        List<CliBackend> backends = List.of(
                new ClaudeCliBackend(props.getClaude().getCommand(), props.getClaude().getMaxBudgetUsd()),
                new QwenCliBackend(props.getQwen().getCommand()));
        return new ModelRouter(backends, props.getAliases());
    }

    @Bean
    ChatCompletionService chatCompletionService(ModelRouter router, CliRunner runner) {
        return new ChatCompletionService(router, runner);
    }
}
