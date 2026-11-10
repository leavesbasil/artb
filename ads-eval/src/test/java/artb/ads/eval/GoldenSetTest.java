package artb.ads.eval;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import artb.ads.campaign.ingest.CampaignDefinition;
import artb.ads.campaign.synthetic.SyntheticCatalog;

class GoldenSetTest {

    @Test
    void casesReferenceExistingCampaignsAndHaveExpectations() {
        Set<String> keys = new HashSet<>();
        SyntheticCatalog.baseProducts().stream().map(CampaignDefinition::key).forEach(keys::add);
        List<GoldenCase> cases = GoldenSet.load();

        assertThat(cases).hasSizeGreaterThanOrEqualTo(30);
        assertThat(cases).extracting(GoldenCase::id).doesNotHaveDuplicates();
        for (GoldenCase c : cases) {
            GoldenCase.Expect e = c.expect();
            assertThat(e.expectsAd() || e.expectsNoAd() || e.forbidden() != null).as(c.id()).isTrue();
            if (e.campaign() != null) {
                assertThat(keys).as(c.id()).contains(e.campaign());
            }
            if (e.acceptable() != null) {
                assertThat(keys).as(c.id()).containsAll(e.acceptable());
            }
            assertThat(c.messages().getLast().role()).as(c.id()).isEqualTo(artb.ads.matching.DialogMessage.Role.CLIENT);
        }
    }
}
