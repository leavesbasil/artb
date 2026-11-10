package artb.llmproxy;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("proxy")
public class ProxyProperties {

    /** Таймаут одной попытки; обычный вызов занимает 4–20 с, при зависании — один повтор. */
    private Duration timeout = Duration.ofSeconds(60);
    /** Сколько процессов одного CLI работает одновременно. */
    private int maxConcurrency = 4;
    /** Если задан — запросы должны нести {@code Authorization: Bearer <key>}. */
    private String apiKey;
    private Claude claude = new Claude();
    private Qwen qwen = new Qwen();
    /**
     * Псевдонимы моделей: имя, которое шлёт клиент → {@code backend/model}.
     * Например {@code fast-model: claude/haiku}. Имена вида {@code claude/sonnet} и {@code qwen} работают без псевдонимов.
     */
    private Map<String, String> aliases = new LinkedHashMap<>();

    public static class Claude {
        private String command = "claude";
        /** Предохранитель на один вызов; судья на sonnet с 20 кандидатами стоит дороже $0.05. */
        private double maxBudgetUsd = 0.5;

        public String getCommand() { return command; }
        public void setCommand(String command) { this.command = command; }
        public double getMaxBudgetUsd() { return maxBudgetUsd; }
        public void setMaxBudgetUsd(double v) { this.maxBudgetUsd = v; }
    }

    public static class Qwen {
        private String command = "qwen";

        public String getCommand() { return command; }
        public void setCommand(String command) { this.command = command; }
    }

    public Duration getTimeout() { return timeout; }
    public void setTimeout(Duration timeout) { this.timeout = timeout; }
    public int getMaxConcurrency() { return maxConcurrency; }
    public void setMaxConcurrency(int v) { this.maxConcurrency = v; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public Claude getClaude() { return claude; }
    public void setClaude(Claude claude) { this.claude = claude; }
    public Qwen getQwen() { return qwen; }
    public void setQwen(Qwen qwen) { this.qwen = qwen; }
    public Map<String, String> getAliases() { return aliases; }
    public void setAliases(Map<String, String> aliases) { this.aliases = aliases; }
}
