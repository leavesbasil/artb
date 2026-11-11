package artb.ads.matching.llm;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import java.util.List;

/** Ответ этапа «Интент»: запросы, коммерческий интент и темы диалога с уверенностью. */
public record IntentResult(
        @JsonPropertyDescription("1–3 коротких описания текущей потребности клиента от третьего лица")
        List<String> queries,
        @JsonPropertyDescription("0..1: есть ли потребность, которую закрывает продукт банка. "
                + "Вопрос о продукте или его условиях — 0.8–1.0, скрытая потребность — 0.4–0.7, нет потребности — 0–0.2")
        double commercialIntent,
        @JsonPropertyDescription("Темы диалога из списка с уверенностью 0..1")
        List<TopicScore> topics) {

    public record TopicScore(String id, double confidence) {}

    public IntentResult {
        queries = queries == null ? List.of() : List.copyOf(queries);
        topics = topics == null ? List.of() : List.copyOf(topics);
    }
}
