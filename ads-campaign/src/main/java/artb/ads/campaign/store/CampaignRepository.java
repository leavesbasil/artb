package artb.ads.campaign.store;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import artb.ads.campaign.model.Campaign;
import artb.ads.campaign.model.CampaignIntent;
import artb.ads.campaign.model.Creative;

/** Каталог кампаний: кампании, креативы и интенты (без векторов — они в {@link IntentIndex}). */
public interface CampaignRepository {

    void save(Campaign campaign);

    Optional<Campaign> find(UUID id);

    Optional<Campaign> findByExternalKey(String externalKey);

    /** ACTIVE и в пределах дат показа. */
    List<Campaign> findLive(Instant now);

    List<Campaign> findAll(int limit);

    void saveCreatives(Collection<Creative> creatives);

    List<Creative> creatives(UUID campaignId);

    Map<UUID, List<Creative>> approvedCreatives(Collection<UUID> campaignIds);

    void saveIntents(Collection<CampaignIntent> intents);

    List<CampaignIntent> intents(UUID campaignId);

    /** Следующая версия каталога: любая активация или пауза меняет её, и eligible set пересчитывается. */
    long nextCatalogVersion();

    long catalogVersion();
}
