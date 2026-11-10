package artb.ads.campaign.store;

import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import artb.ads.campaign.model.Campaign;
import artb.ads.campaign.model.CampaignIntent;
import artb.ads.campaign.model.Creative;

/** Каталог в памяти: тесты и прогон golden set без базы. */
public class InMemoryCampaignRepository implements CampaignRepository {

    private final Map<UUID, Campaign> campaigns = new ConcurrentHashMap<>();
    private final Map<UUID, Creative> creatives = new ConcurrentHashMap<>();
    private final Map<UUID, CampaignIntent> intents = new ConcurrentHashMap<>();
    private final AtomicLong versions = new AtomicLong();

    @Override
    public void save(Campaign campaign) {
        campaigns.put(campaign.id(), campaign);
    }

    @Override
    public Optional<Campaign> find(UUID id) {
        return Optional.ofNullable(campaigns.get(id));
    }

    @Override
    public Optional<Campaign> findByExternalKey(String externalKey) {
        return campaigns.values().stream().filter(c -> externalKey.equals(c.externalKey())).findFirst();
    }

    @Override
    public List<Campaign> findLive(Instant now) {
        return campaigns.values().stream().filter(c -> c.isLive(now)).sorted(Comparator.comparing(Campaign::name)).toList();
    }

    @Override
    public List<Campaign> findAll(int limit) {
        return campaigns.values().stream().sorted(Comparator.comparing(Campaign::updatedAt).reversed()).limit(limit).toList();
    }

    @Override
    public void saveCreatives(Collection<Creative> list) {
        list.forEach(c -> creatives.put(c.id(), c));
    }

    @Override
    public List<Creative> creatives(UUID campaignId) {
        return creatives.values().stream().filter(c -> c.campaignId().equals(campaignId))
                .sorted(Comparator.comparing(Creative::type).thenComparing(Creative::id)).toList();
    }

    @Override
    public Map<UUID, List<Creative>> approvedCreatives(Collection<UUID> campaignIds) {
        Map<UUID, List<Creative>> result = new LinkedHashMap<>();
        for (UUID id : campaignIds) {
            List<Creative> list = creatives(id).stream().filter(Creative::approved).toList();
            if (!list.isEmpty()) {
                result.put(id, list);
            }
        }
        return result;
    }

    @Override
    public void saveIntents(Collection<CampaignIntent> list) {
        list.forEach(i -> intents.put(i.id(), i));
    }

    @Override
    public List<CampaignIntent> intents(UUID campaignId) {
        return intents.values().stream().filter(i -> i.campaignId().equals(campaignId)).toList();
    }

    @Override
    public long nextCatalogVersion() {
        return versions.incrementAndGet();
    }

    @Override
    public long catalogVersion() {
        return campaigns.values().stream().mapToLong(Campaign::catalogVersion).max().orElse(0);
    }
}
