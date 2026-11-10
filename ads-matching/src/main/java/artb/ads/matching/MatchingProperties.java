package artb.ads.matching;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("ads.matching")
public class MatchingProperties {

    /** Сколько последних реплик видят модели. */
    private int historyTurns = 6;
    /** Вызывать ли модель в гейте для проверки стадии диалога. */
    private boolean gateLlmCheck = true;
    private double commercialIntentThreshold = 0.3;
    /** С какой уверенности тема диалога считается его темой. */
    private double topicConfidence = 0.5;
    /** Порог для стоп-тем ниже: лучше лишний раз не показать. */
    private double sensitiveTopicConfidence = 0.3;
    private int topK = 20;
    private int hitsPerQuery = 80;
    private double minSimilarity = 0.2;
    /**
     * Кампания отбрасывается, только если негативный интент ближе позитивного на этот запас.
     * Лексические эмбеддинги не отличают «оформить карту» от «закрыть карту», поэтому запас нужен;
     * со смысловыми эмбеддингами его можно уменьшить. Смысловую проверку дублирует судья.
     */
    private double negativeMargin = 0.1;
    /** Если по темам нашлось меньше кандидатов, поиск расширяется на весь eligible set. */
    private int minTopicCandidates = 5;
    /** Сходство запросов с прошлым ходом, выше которого retrieval и судья берутся из кэша. */
    private double cacheQuerySimilarity = 0.9;
    private Duration deadline = Duration.ofMillis(2500);
    /** В CLI-профилях дедлайн выключен: вызов CLI занимает секунды. */
    private boolean deadlineEnabled = true;

    public int getHistoryTurns() { return historyTurns; }
    public void setHistoryTurns(int v) { this.historyTurns = v; }
    public boolean isGateLlmCheck() { return gateLlmCheck; }
    public void setGateLlmCheck(boolean v) { this.gateLlmCheck = v; }
    public double getCommercialIntentThreshold() { return commercialIntentThreshold; }
    public void setCommercialIntentThreshold(double v) { this.commercialIntentThreshold = v; }
    public double getTopicConfidence() { return topicConfidence; }
    public void setTopicConfidence(double v) { this.topicConfidence = v; }
    public double getSensitiveTopicConfidence() { return sensitiveTopicConfidence; }
    public void setSensitiveTopicConfidence(double v) { this.sensitiveTopicConfidence = v; }
    public int getTopK() { return topK; }
    public void setTopK(int v) { this.topK = v; }
    public int getHitsPerQuery() { return hitsPerQuery; }
    public void setHitsPerQuery(int v) { this.hitsPerQuery = v; }
    public double getMinSimilarity() { return minSimilarity; }
    public void setMinSimilarity(double v) { this.minSimilarity = v; }
    public double getNegativeMargin() { return negativeMargin; }
    public void setNegativeMargin(double v) { this.negativeMargin = v; }
    public int getMinTopicCandidates() { return minTopicCandidates; }
    public void setMinTopicCandidates(int v) { this.minTopicCandidates = v; }
    public double getCacheQuerySimilarity() { return cacheQuerySimilarity; }
    public void setCacheQuerySimilarity(double v) { this.cacheQuerySimilarity = v; }
    public Duration getDeadline() { return deadline; }
    public void setDeadline(Duration v) { this.deadline = v; }
    public boolean isDeadlineEnabled() { return deadlineEnabled; }
    public void setDeadlineEnabled(boolean v) { this.deadlineEnabled = v; }
}
