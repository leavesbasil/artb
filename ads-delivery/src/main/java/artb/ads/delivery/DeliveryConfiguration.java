package artb.ads.delivery;

import java.time.Clock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;

import artb.ads.matching.AdMatcher;
import artb.ads.targeting.EligibleSetService;
import tools.jackson.databind.json.JsonMapper;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DeliveryProperties.class)
public class DeliveryConfiguration {

    private static final Logger log = LoggerFactory.getLogger(DeliveryConfiguration.class);

    @Bean
    OfferTokenService offerTokenService(DeliveryProperties props) {
        return new OfferTokenService(props.getTokenSecret());
    }

    @Bean
    OfferRepository offerRepository(JdbcClient jdbc, JsonMapper json) {
        return new OfferRepository(jdbc, json);
    }

    @Bean
    FrequencyPolicy frequencyPolicy(OfferRepository offers, DeliveryProperties props, Clock clock) {
        return new FrequencyPolicy(offers, props, clock);
    }

    @Bean
    AdServingService adServingService(AdMatcher matcher, OfferRepository offers, OfferTokenService tokens,
            EligibleSetService eligibleSets, DeliveryProperties props, Clock clock) {
        return new AdServingService(matcher, offers, tokens, eligibleSets, props, clock);
    }

    @Bean
    EventService eventService(OfferRepository offers, OfferTokenService tokens, DeliveryProperties props, Clock clock) {
        return new EventService(offers, tokens, props, clock);
    }

    @Bean
    OfferExpiryJob offerExpiryJob(OfferRepository offers, Clock clock) {
        return new OfferExpiryJob(offers, clock);
    }

    /** Переводит невыданные вовремя офферы в EXPIRED. */
    public static class OfferExpiryJob {
        private final OfferRepository offers;
        private final Clock clock;

        OfferExpiryJob(OfferRepository offers, Clock clock) {
            this.offers = offers;
            this.clock = clock;
        }

        @Scheduled(fixedDelayString = "${ads.delivery.expiry-interval:10s}")
        public void expire() {
            int n = offers.expire(clock.instant());
            if (n > 0) {
                log.debug("Истекло офферов без показа: {}", n);
            }
        }
    }
}
