package artb.ads.targeting;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import artb.ads.campaign.store.CampaignRepository;

/**
 * Считает eligible set один раз в начале сессии, без LLM. Пересчёт — если сменилась версия каталога
 * (кампанию включили или поставили на паузу) или истёк TTL.
 */
public class EligibleSetService {

    private final CampaignRepository campaigns;
    private final SegmentFilter filter;
    private final ClientSegmentsProvider segments;
    private final Clock clock;
    private final Duration ttl;
    private final Map<String, EligibleSet> bySession = new ConcurrentHashMap<>();

    public EligibleSetService(CampaignRepository campaigns, SegmentFilter filter, ClientSegmentsProvider segments,
            Clock clock, Duration ttl) {
        this.campaigns = campaigns;
        this.filter = filter;
        this.segments = segments;
        this.clock = clock;
        this.ttl = ttl;
    }

    public EligibleSet forSession(String sessionId, String clientId) {
        long version = campaigns.catalogVersion();
        Instant now = clock.instant();
        return bySession.compute(sessionId, (id, cached) -> {
            if (cached != null && cached.catalogVersion() == version && cached.clientId().equals(clientId)
                    && cached.computedAt().plus(ttl).isAfter(now)) {
                return cached;
            }
            return compute(clientId, version, now);
        });
    }

    /** Есть ли уже посчитанный набор для сессии (сессия начата). */
    public boolean hasSession(String sessionId) {
        return bySession.containsKey(sessionId);
    }

    public EligibleSet compute(String clientId, long version, Instant now) {
        Set<String> clientSegments = segments.segments(clientId);
        Set<UUID> ids = campaigns.findLive(now).stream()
                .filter(c -> filter.isEligible(c, clientSegments))
                .map(c -> c.id())
                .collect(Collectors.toUnmodifiableSet());
        return new EligibleSet(clientId, version, ids, now);
    }
}
