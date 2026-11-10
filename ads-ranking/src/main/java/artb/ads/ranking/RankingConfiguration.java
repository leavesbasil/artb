package artb.ads.ranking;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class RankingConfiguration {

    @Bean
    @ConditionalOnMissingBean
    CandidateRanker candidateRanker(@Value("${ads.ranking.min-relevance:0.6}") double minRelevance) {
        return new RelevancePriorityRanker(minRelevance);
    }
}
