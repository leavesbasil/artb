package artb.ads.targeting;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import artb.ads.campaign.model.Campaign;
import artb.ads.campaign.model.CampaignStatus;

class BlacklistSegmentFilterTest {

    private final BlacklistSegmentFilter filter = new BlacklistSegmentFilter();

    @Test
    void excludesClientsFromBlacklistedSegments() {
        Campaign creditCard = campaign(List.of("minor", "has_credit_card"));
        assertThat(filter.isEligible(creditCard, Set.of("salary_client"))).isTrue();
        assertThat(filter.isEligible(creditCard, Set.of("salary_client", "has_credit_card"))).isFalse();
    }

    @Test
    void emptyBlacklistAdmitsEveryone() {
        assertThat(filter.isEligible(campaign(List.of()), Set.of("minor"))).isTrue();
    }

    @Test
    void mockSegmentsAreDeterministic() {
        var provider = new MockClientSegmentsProvider(java.util.Map.of("c-1", List.of("minor")),
                List.of("a", "b", "c", "d"), 0.5);
        assertThat(provider.segments("c-1")).containsExactly("minor");
        assertThat(provider.segments("c-2")).isEqualTo(provider.segments("c-2"));
    }

    private static Campaign campaign(List<String> excluded) {
        return new Campaign(UUID.randomUUID(), null, "x", CampaignStatus.ACTIVE, "d", List.of(), 50, null, null,
                excluded, List.of(), 1, Instant.now());
    }
}
