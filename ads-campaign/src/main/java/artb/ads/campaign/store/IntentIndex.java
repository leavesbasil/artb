package artb.ads.campaign.store;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import artb.ads.campaign.model.IntentPolarity;

/** Векторный индекс одобренных интентов кампаний. */
public interface IntentIndex {

    record IndexedIntent(UUID intentId, String text, IntentPolarity polarity, float[] vector) {}

    record IntentHit(UUID campaignId, UUID intentId, String text, IntentPolarity polarity, double similarity) {}

    /** Заменяет векторы интентов кампании. */
    void index(UUID campaignId, String modelId, List<IndexedIntent> intents);

    /** Ближайшие интенты (обеих полярностей) среди указанных кампаний, только векторы той же модели. */
    List<IntentHit> search(float[] query, String modelId, Collection<UUID> campaignIds, int limit);
}
