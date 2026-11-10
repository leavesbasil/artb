package artb.ads.matching;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import artb.ads.llm.embedding.TextEmbedder;
import artb.ads.matching.CandidateRetriever.Retrieved;
import artb.ads.ranking.JudgedCandidate;

/**
 * Кэш retrieval и судьи по сессии. Если набор тем не изменился и запросы почти те же, этапы 3–4
 * не запускаются: тема разговора часто держится несколько ходов, а судья — самый дорогой этап.
 */
public class SessionCache {

    public record Entry(Set<String> topics, float[] centroid, long catalogVersion, List<Retrieved> retrieved,
            List<JudgedCandidate> judged) {}

    private final Map<String, Entry> entries = Collections.synchronizedMap(new LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Entry> eldest) {
            return size() > 10_000;
        }
    });

    public Entry find(String sessionId, Set<String> topics, float[] centroid, long catalogVersion, double minSimilarity) {
        Entry e = entries.get(sessionId);
        if (e == null || e.catalogVersion() != catalogVersion || !e.topics().equals(topics)) {
            return null;
        }
        return TextEmbedder.cosine(e.centroid(), centroid) >= minSimilarity ? e : null;
    }

    public void put(String sessionId, Entry entry) {
        entries.put(sessionId, entry);
    }

    public static float[] centroid(List<float[]> vectors) {
        float[] c = new float[vectors.getFirst().length];
        for (float[] v : vectors) {
            for (int i = 0; i < c.length; i++) {
                c[i] += v[i] / vectors.size();
            }
        }
        return c;
    }
}
