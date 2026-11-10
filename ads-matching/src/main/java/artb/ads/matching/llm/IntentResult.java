package artb.ads.matching.llm;

import java.util.List;

/** Ответ этапа «Интент»: запросы, коммерческий интент и темы диалога с уверенностью. */
public record IntentResult(List<String> queries, double commercialIntent, List<TopicScore> topics) {

    public record TopicScore(String id, double confidence) {}

    public IntentResult {
        queries = queries == null ? List.of() : List.copyOf(queries);
        topics = topics == null ? List.of() : List.copyOf(topics);
    }
}
