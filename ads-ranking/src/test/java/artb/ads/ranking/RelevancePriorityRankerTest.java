package artb.ads.ranking;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import artb.ads.campaign.model.AdFormat;

class RelevancePriorityRankerTest {

    private final RelevancePriorityRanker ranker = new RelevancePriorityRanker(0.6);
    private final RankingContext ctx = new RankingContext("s", 1, Instant.now());

    @Test
    void dropsBelowThresholdAndMultipliesByPriority() {
        JudgedCandidate relevantLowPriority = candidate(0.9, 40);
        JudgedCandidate lessRelevantHighPriority = candidate(0.65, 90);
        JudgedCandidate irrelevantTopPriority = candidate(0.3, 100);

        List<RankedCandidate> ranked = ranker.rank(List.of(relevantLowPriority, lessRelevantHighPriority, irrelevantTopPriority), ctx);

        assertThat(ranked).extracting(RankedCandidate::candidate).containsExactly(lessRelevantHighPriority, relevantLowPriority);
    }

    @Test
    void emptyWhenNothingRelevant() {
        assertThat(ranker.rank(List.of(candidate(0.2, 100)), ctx)).isEmpty();
    }

    private static JudgedCandidate candidate(double relevance, int priority) {
        return new JudgedCandidate(UUID.randomUUID(), UUID.randomUUID(), AdFormat.CARD, relevance, "r", priority, 0.5);
    }
}
