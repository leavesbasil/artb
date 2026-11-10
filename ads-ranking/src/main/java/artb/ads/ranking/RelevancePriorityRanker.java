package artb.ads.ranking;

import java.util.Comparator;
import java.util.List;

/**
 * Прототип: отсечка по релевантности τ, затем relevance × приоритет кампании.
 * Релевантность — множитель, чтобы приоритет не перевешивал контекст диалога.
 */
public class RelevancePriorityRanker implements CandidateRanker {

    private final double minRelevance;

    public RelevancePriorityRanker(double minRelevance) {
        this.minRelevance = minRelevance;
    }

    @Override
    public List<RankedCandidate> rank(List<JudgedCandidate> candidates, RankingContext ctx) {
        return candidates.stream()
                .filter(c -> c.relevance() >= minRelevance)
                .map(c -> new RankedCandidate(c, c.relevance() * Math.max(1, c.priority()) / 100.0))
                .sorted(Comparator.comparingDouble(RankedCandidate::score).reversed()
                        .thenComparing(r -> r.candidate().relevance(), Comparator.reverseOrder()))
                .toList();
    }
}
