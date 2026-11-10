package artb.ads.delivery;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import artb.ads.matching.MatchOutcome.CandidateTrace;
import artb.ads.matching.llm.IntentResult.TopicScore;

/** Строка ad_offer: решение на ходе со всеми кандидатами — для отладки и будущего обучения. */
public record OfferRecord(
        UUID id,
        String sessionId,
        String clientId,
        int turnNo,
        Instant createdAt,
        Instant expiresAt,
        OfferStatus status,
        List<String> intentQueries,
        Double commercialIntent,
        List<TopicScore> topics,
        List<CandidateTrace> candidates,
        UUID campaignId,
        UUID creativeId,
        String format,
        Double relevance,
        Double score,
        String reason,
        String noAdReason,
        boolean fromCache,
        Map<String, Long> latencyMs,
        Map<String, String> models) {
}
