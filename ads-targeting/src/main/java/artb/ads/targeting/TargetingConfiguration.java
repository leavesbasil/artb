package artb.ads.targeting;

import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import artb.ads.campaign.store.CampaignRepository;
import artb.ads.campaign.synthetic.SyntheticCatalog;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(TargetingConfiguration.TargetingProperties.class)
public class TargetingConfiguration {

    @ConfigurationProperties("ads.targeting")
    public static class TargetingProperties {
        private Duration eligibleSetTtl = Duration.ofMinutes(15);
        /** Явные сегменты мок-клиентов: clientId → список сегментов. */
        private Map<String, List<String>> mockClients = new HashMap<>();
        /** Вероятность сегмента у мок-клиента без явного профиля. */
        private double mockSegmentProbability = 0.15;

        public Duration getEligibleSetTtl() { return eligibleSetTtl; }
        public void setEligibleSetTtl(Duration eligibleSetTtl) { this.eligibleSetTtl = eligibleSetTtl; }
        public Map<String, List<String>> getMockClients() { return mockClients; }
        public void setMockClients(Map<String, List<String>> mockClients) { this.mockClients = mockClients; }
        public double getMockSegmentProbability() { return mockSegmentProbability; }
        public void setMockSegmentProbability(double p) { this.mockSegmentProbability = p; }
    }

    @Bean
    @ConditionalOnMissingBean
    SegmentFilter segmentFilter() {
        return new BlacklistSegmentFilter();
    }

    @Bean
    @ConditionalOnMissingBean
    ClientSegmentsProvider clientSegmentsProvider(TargetingProperties props) {
        return new MockClientSegmentsProvider(props.getMockClients(), SyntheticCatalog.SEGMENTS,
                props.getMockSegmentProbability());
    }

    @Bean
    EligibleSetService eligibleSetService(CampaignRepository campaigns, SegmentFilter filter,
            ClientSegmentsProvider segments, Clock clock, TargetingProperties props) {
        return new EligibleSetService(campaigns, filter, segments, clock, props.getEligibleSetTtl());
    }
}
