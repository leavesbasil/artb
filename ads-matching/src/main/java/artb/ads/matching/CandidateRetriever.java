package artb.ads.matching;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import artb.ads.campaign.model.Campaign;
import artb.ads.campaign.model.IntentPolarity;
import artb.ads.campaign.store.IntentIndex;
import artb.ads.campaign.store.IntentIndex.IntentHit;
import artb.ads.llm.embedding.TextEmbedder;

/**
 * Этап 3: векторный поиск по интентам кампаний из eligible set. Сначала среди кампаний с теми же
 * темами; если кандидатов мало — по всему eligible set, чтобы не терять скрытый интент
 * (ремонт → кредит). Кампания отбрасывается, если запрос заметно ближе к её негативному интенту.
 */
public class CandidateRetriever {

    public record Retrieved(UUID campaignId, double similarity, String matchedIntent) {}

    private record Score(double positive, String intent, double negative) {}

    private final IntentIndex index;
    private final TextEmbedder embedder;
    private final MatchingProperties props;

    public CandidateRetriever(IntentIndex index, TextEmbedder embedder, MatchingProperties props) {
        this.index = index;
        this.embedder = embedder;
        this.props = props;
    }

    public List<float[]> embed(List<String> queries) {
        return embedder.embedAll(queries);
    }

    public List<Retrieved> retrieve(List<float[]> queryVectors, Collection<Campaign> pool, Set<String> dialogTopics) {
        if (queryVectors.isEmpty() || pool.isEmpty()) {
            return List.of();
        }
        List<UUID> topical = pool.stream()
                .filter(c -> !Collections.disjoint(c.topics(), dialogTopics))
                .map(Campaign::id).toList();
        Map<UUID, Score> scores = new HashMap<>();
        if (!topical.isEmpty()) {
            search(queryVectors, topical, scores);
        }
        if (accepted(scores).size() < props.getMinTopicCandidates()) {
            search(queryVectors, pool.stream().map(Campaign::id).toList(), scores);
        }
        return accepted(scores).stream()
                .sorted(Comparator.comparingDouble(Retrieved::similarity).reversed())
                .limit(props.getTopK())
                .toList();
    }

    private void search(List<float[]> queryVectors, List<UUID> campaignIds, Map<UUID, Score> scores) {
        for (float[] q : queryVectors) {
            for (IntentHit hit : index.search(q, embedder.modelId(), campaignIds, props.getHitsPerQuery())) {
                scores.merge(hit.campaignId(), toScore(hit), CandidateRetriever::max);
            }
        }
    }

    private List<Retrieved> accepted(Map<UUID, Score> scores) {
        List<Retrieved> result = new ArrayList<>();
        scores.forEach((id, s) -> {
            if (s.positive() >= props.getMinSimilarity() && s.negative() - s.positive() < props.getNegativeMargin()) {
                result.add(new Retrieved(id, s.positive(), s.intent()));
            }
        });
        return result;
    }

    private static Score toScore(IntentHit hit) {
        return hit.polarity() == IntentPolarity.POSITIVE
                ? new Score(hit.similarity(), hit.text(), Double.NEGATIVE_INFINITY)
                : new Score(Double.NEGATIVE_INFINITY, null, hit.similarity());
    }

    private static Score max(Score a, Score b) {
        boolean aPos = a.positive() >= b.positive();
        return new Score(Math.max(a.positive(), b.positive()), aPos ? a.intent() : b.intent(),
                Math.max(a.negative(), b.negative()));
    }
}
