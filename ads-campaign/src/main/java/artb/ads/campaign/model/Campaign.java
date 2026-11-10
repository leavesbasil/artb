package artb.ads.campaign.model;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record Campaign(
        UUID id,
        String externalKey,
        String name,
        CampaignStatus status,
        String businessDescription,
        List<String> topics,
        int priority,
        Instant startAt,
        Instant endAt,
        List<String> excludedSegments,
        List<String> excludedTopics,
        long catalogVersion,
        Instant updatedAt) {

    public Campaign {
        topics = List.copyOf(topics);
        excludedSegments = List.copyOf(excludedSegments);
        excludedTopics = List.copyOf(excludedTopics);
    }

    public boolean isLive(Instant now) {
        return status == CampaignStatus.ACTIVE
                && (startAt == null || !now.isBefore(startAt))
                && (endAt == null || now.isBefore(endAt));
    }

    public Campaign withStatus(CampaignStatus newStatus, long newCatalogVersion, Instant now) {
        return new Campaign(id, externalKey, name, newStatus, businessDescription, topics, priority, startAt, endAt,
                excludedSegments, excludedTopics, newCatalogVersion, now);
    }

    public Campaign withTopics(List<String> newTopics, Instant now) {
        return new Campaign(id, externalKey, name, status, businessDescription, newTopics, priority, startAt, endAt,
                excludedSegments, excludedTopics, catalogVersion, now);
    }
}
