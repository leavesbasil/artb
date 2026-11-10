package artb.ads.matching;

import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import artb.ads.campaign.model.Campaign;
import artb.ads.campaign.store.CampaignRepository;

/** Живые кампании в памяти, перечитываются при смене версии каталога. */
public class CatalogSnapshot {

    private record Snapshot(long version, Map<UUID, Campaign> campaigns) {}

    private final CampaignRepository repo;
    private final Clock clock;
    private volatile Snapshot current = new Snapshot(-1, Map.of());

    public CatalogSnapshot(CampaignRepository repo, Clock clock) {
        this.repo = repo;
        this.clock = clock;
    }

    public Map<UUID, Campaign> live() {
        long version = repo.catalogVersion();
        Snapshot s = current;
        if (s.version() != version) {
            synchronized (this) {
                s = current;
                if (s.version() != version) {
                    s = new Snapshot(version, repo.findLive(clock.instant()).stream()
                            .collect(Collectors.toUnmodifiableMap(Campaign::id, Function.identity())));
                    current = s;
                }
            }
        }
        return s.campaigns();
    }
}
