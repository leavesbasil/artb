package artb.ads.ranking;

import java.util.UUID;

import artb.ads.campaign.model.AdFormat;

/** Кандидат после LLM-судьи. */
public record JudgedCandidate(
        UUID campaignId,
        UUID creativeId,
        AdFormat format,
        double relevance,
        String reason,
        int priority,
        double retrievalSimilarity) {
}
