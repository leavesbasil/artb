package artb.ads.campaign.store;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import artb.ads.llm.embedding.TextEmbedder;

public class InMemoryIntentIndex implements IntentIndex {

    private record Entry(String modelId, List<IndexedIntent> intents) {}

    private final Map<UUID, Entry> byCampaign = new ConcurrentHashMap<>();

    @Override
    public void index(UUID campaignId, String modelId, List<IndexedIntent> intents) {
        byCampaign.put(campaignId, new Entry(modelId, List.copyOf(intents)));
    }

    @Override
    public List<IntentHit> search(float[] query, String modelId, Collection<UUID> campaignIds, int limit) {
        Set<UUID> ids = new HashSet<>(campaignIds);
        List<IntentHit> hits = new ArrayList<>();
        byCampaign.forEach((campaignId, entry) -> {
            if (!ids.contains(campaignId) || !entry.modelId().equals(modelId)) {
                return;
            }
            for (IndexedIntent i : entry.intents()) {
                hits.add(new IntentHit(campaignId, i.intentId(), i.text(), i.polarity(), TextEmbedder.cosine(query, i.vector())));
            }
        });
        return hits.stream().sorted(Comparator.comparingDouble(IntentHit::similarity).reversed()).limit(limit).toList();
    }
}
