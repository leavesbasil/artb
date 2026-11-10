package artb.ads.campaign;

import java.time.Clock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

import artb.ads.campaign.ingest.CampaignDefinition;
import artb.ads.campaign.ingest.CampaignService;
import artb.ads.campaign.ingest.CreativeValidator;
import artb.ads.campaign.ingest.EnrichmentService;
import artb.ads.campaign.model.TaxonomyProvider;
import artb.ads.campaign.store.CampaignRepository;
import artb.ads.campaign.store.IntentIndex;
import artb.ads.campaign.store.JdbcCampaignRepository;
import artb.ads.campaign.store.PgIntentIndex;
import artb.ads.campaign.store.TopicRepository;
import artb.ads.campaign.synthetic.SyntheticCatalog;
import artb.ads.llm.LlmGateway;
import artb.ads.llm.embedding.TextEmbedder;

@Configuration(proxyBeanMethods = false)
public class CampaignConfiguration {

    private static final Logger log = LoggerFactory.getLogger(CampaignConfiguration.class);

    @Bean
    @ConditionalOnMissingBean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    @ConditionalOnMissingBean
    CampaignRepository campaignRepository(JdbcClient jdbc) {
        return new JdbcCampaignRepository(jdbc);
    }

    @Bean
    @ConditionalOnMissingBean
    IntentIndex intentIndex(JdbcClient jdbc) {
        return new PgIntentIndex(jdbc);
    }

    @Bean
    @ConditionalOnMissingBean
    TaxonomyProvider taxonomyProvider(JdbcClient jdbc) {
        return new TopicRepository(jdbc);
    }

    @Bean
    CreativeValidator creativeValidator() {
        return new CreativeValidator();
    }

    @Bean
    EnrichmentService enrichmentService(LlmGateway llm) {
        return new EnrichmentService(llm);
    }

    @Bean
    CampaignService campaignService(CampaignRepository repo, IntentIndex index, TextEmbedder embedder,
            EnrichmentService enrichment, TaxonomyProvider taxonomy, CreativeValidator validator, Clock clock) {
        return new CampaignService(repo, index, embedder, enrichment, taxonomy, validator, clock);
    }

    /** Заполняет пустой каталог синтетическими кампаниями: ads.catalog.synthetic-count=1000 */
    @Bean
    ApplicationRunner syntheticCatalogSeeder(CampaignRepository repo, CampaignService service,
            @Value("${ads.catalog.synthetic-count:0}") int count, @Value("${ads.catalog.synthetic-seed:42}") long seed) {
        return args -> {
            if (count <= 0 || repo.catalogVersion() > 0) {
                return;
            }
            long started = System.currentTimeMillis();
            for (CampaignDefinition def : SyntheticCatalog.generate(count, seed)) {
                service.importActive(def, "synthetic");
            }
            log.info("Синтетический каталог: {} кампаний за {} мс", count, System.currentTimeMillis() - started);
        };
    }
}
