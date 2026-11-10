package artb.ads.targeting;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/** Кампании, допущенные к клиенту на эту сессию. */
public record EligibleSet(String clientId, long catalogVersion, Set<UUID> campaignIds, Instant computedAt) {

    public EligibleSet {
        campaignIds = Set.copyOf(campaignIds);
    }
}
