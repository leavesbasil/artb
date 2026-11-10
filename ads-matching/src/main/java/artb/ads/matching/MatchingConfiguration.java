package artb.ads.matching;

import java.time.Clock;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import artb.ads.campaign.model.TaxonomyProvider;
import artb.ads.campaign.store.CampaignRepository;
import artb.ads.campaign.store.IntentIndex;
import artb.ads.llm.LlmGateway;
import artb.ads.llm.embedding.TextEmbedder;
import artb.ads.ranking.CandidateRanker;
import artb.ads.targeting.EligibleSetService;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MatchingProperties.class)
public class MatchingConfiguration {

    @Bean
    CatalogSnapshot catalogSnapshot(CampaignRepository repo, Clock clock) {
        return new CatalogSnapshot(repo, clock);
    }

    @Bean
    SessionCache sessionCache() {
        return new SessionCache();
    }

    @Bean
    AdMatcher adMatcher(EligibleSetService eligibleSets, LlmGateway llm, IntentIndex index, TextEmbedder embedder,
            CandidateRanker ranker, ObjectProvider<FrequencyGuard> frequency, CatalogSnapshot catalog,
            CampaignRepository repo, TaxonomyProvider taxonomy, SessionCache cache, MatchingProperties props, Clock clock) {
        FrequencyGuard guard = frequency.getIfAvailable(() -> FrequencyGuard.NONE);
        return new AdMatcher(eligibleSets, new Gate(guard, llm, props), new IntentExtractor(llm, props),
                new CandidateRetriever(index, embedder, props), new Judge(llm, props), ranker, guard, catalog, repo,
                taxonomy, cache, props, clock);
    }
}
