package artb.ads.matching.llm;

import java.util.List;

/** Ответ LLM-судьи по всем кандидатам одним вызовом. */
public record JudgeResult(List<Judgement> judgements) {

    /**
     * @param candidateId короткий id кандидата из промпта (c1, c2, …)
     * @param creativeId  короткий id креатива из промпта (c1-card, c1-q2, …)
     */
    public record Judgement(String candidateId, double relevance, String format, String creativeId, String reason) {}

    public JudgeResult {
        judgements = judgements == null ? List.of() : List.copyOf(judgements);
    }
}
