package artb.ads.campaign;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import artb.ads.campaign.ingest.CampaignService;
import artb.ads.campaign.ingest.CreativeValidator;
import artb.ads.campaign.ingest.EnrichmentService;
import artb.ads.campaign.model.TaxonomyProvider;
import artb.ads.campaign.model.TopicTaxonomy;
import artb.ads.campaign.store.CampaignRepository;
import artb.ads.campaign.store.IntentIndex;
import artb.ads.llm.embedding.HashingTextEmbedder;

final class TestCatalogs {

    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-09T12:00:00Z"), ZoneOffset.UTC);

    private TestCatalogs() {
    }

    static CampaignService service(CampaignRepository repo, IntentIndex index) {
        TopicTaxonomy taxonomy = TaxonomyProvider.fromMigration();
        return new CampaignService(repo, index, new HashingTextEmbedder(384), new EnrichmentService(null),
                () -> taxonomy, new CreativeValidator(), CLOCK);
    }
}
