package artb.ads.campaign;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import artb.ads.campaign.ingest.CampaignDefinition;
import artb.ads.campaign.ingest.CampaignService;
import artb.ads.campaign.model.TaxonomyProvider;
import artb.ads.campaign.model.TopicTaxonomy;
import artb.ads.campaign.store.InMemoryCampaignRepository;
import artb.ads.campaign.store.InMemoryIntentIndex;
import artb.ads.campaign.synthetic.SyntheticCatalog;

class SyntheticCatalogTest {

    @Test
    void baseProductsUseKnownNonSensitiveTopics() {
        TopicTaxonomy taxonomy = TaxonomyProvider.fromMigration();
        List<CampaignDefinition> base = SyntheticCatalog.baseProducts();
        assertThat(base).hasSize(17);
        assertThat(base).allSatisfy(d -> assertThat(d.topics()).isNotEmpty()
                .allMatch(taxonomy::contains)
                .noneMatch(taxonomy::isSensitive));
        assertThat(base).allSatisfy(d -> assertThat(d.excludedSegments()).allMatch(SyntheticCatalog.SEGMENTS::contains));
    }

    @Test
    void thousandCampaignsImportAndPassValidation() {
        List<CampaignDefinition> defs = SyntheticCatalog.generate(1000, 42);
        Set<String> keys = new HashSet<>();
        defs.forEach(d -> keys.add(d.key()));
        assertThat(keys).hasSize(1000);

        var repo = new InMemoryCampaignRepository();
        CampaignService service = TestCatalogs.service(repo, new InMemoryIntentIndex());
        defs.forEach(d -> service.importActive(d, "test"));

        assertThat(repo.findLive(TestCatalogs.CLOCK.instant())).hasSize(1000);
    }

    @Test
    void taxonomyFromMigrationHasStopTopics() {
        TopicTaxonomy taxonomy = TaxonomyProvider.fromMigration();
        assertThat(taxonomy.all()).hasSizeGreaterThan(30);
        assertThat(taxonomy.sensitiveIds()).contains("financial_hardship", "fraud", "debt_collection");
    }
}
