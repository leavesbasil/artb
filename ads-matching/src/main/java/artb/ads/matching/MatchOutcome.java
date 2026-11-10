package artb.ads.matching;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import artb.ads.campaign.model.AdFormat;
import artb.ads.campaign.model.Campaign;
import artb.ads.campaign.model.Creative;
import artb.ads.matching.llm.IntentResult;

/** Итог подбора на ходе: выбранная реклама или причина её отсутствия, плюс всё для отладки и логов. */
public record MatchOutcome(
        Optional<Selection> selection,
        NoAdReason noAdReason,
        IntentResult intent,
        List<CandidateTrace> candidates,
        Map<String, Long> latencyMs,
        Map<String, String> models,
        boolean fromCache) {

    public record Selection(Campaign campaign, Creative creative, AdFormat format, double relevance, double score,
            String reason) {}

    public record CandidateTrace(UUID campaignId, String name, double similarity, Double relevance, Double score,
            String reason) {}

    public MatchOutcome withLatency(Map<String, Long> latency) {
        return new MatchOutcome(selection, noAdReason, intent, candidates, Map.copyOf(latency), models, fromCache);
    }

    public static MatchOutcome noAd(NoAdReason reason, IntentResult intent, List<CandidateTrace> candidates, Trace trace,
            boolean fromCache) {
        return new MatchOutcome(Optional.empty(), reason, intent, candidates, trace.latencyMs(), trace.models(), fromCache);
    }
}
