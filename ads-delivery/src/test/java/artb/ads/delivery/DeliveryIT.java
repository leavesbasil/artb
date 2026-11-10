package artb.ads.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import artb.ads.campaign.ingest.CampaignService;
import artb.ads.campaign.ingest.CreativeValidator;
import artb.ads.campaign.ingest.EnrichmentService;
import artb.ads.campaign.model.AdFormat;
import artb.ads.campaign.model.Campaign;
import artb.ads.campaign.model.Creative;
import artb.ads.campaign.model.TaxonomyProvider;
import artb.ads.campaign.store.JdbcCampaignRepository;
import artb.ads.campaign.store.PgIntentIndex;
import artb.ads.campaign.synthetic.SyntheticCatalog;
import artb.ads.delivery.AdServingService.MatchRequest;
import artb.ads.delivery.EventService.EventRequest;
import artb.ads.delivery.EventService.Result;
import artb.ads.llm.embedding.HashingTextEmbedder;
import artb.ads.matching.DialogMessage;
import artb.ads.matching.MatchOutcome;
import artb.ads.matching.MatchOutcome.Selection;
import artb.ads.matching.NoAdReason;
import artb.ads.matching.Trace;
import artb.ads.targeting.BlacklistSegmentFilter;
import artb.ads.targeting.EligibleSetService;
import artb.ads.targeting.MockClientSegmentsProvider;
import tools.jackson.databind.json.JsonMapper;

/** Жизненный цикл оффера на настоящей базе: выдача, показ, повтор, скрытие, лимиты и истечение. */
@Testcontainers
class DeliveryIT {

    @Container
    static final PostgreSQLContainer PG = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));

    static JdbcClient jdbc;
    static Campaign loan;
    static Creative loanQuestion;

    MutableClock clock = new MutableClock(Instant.parse("2026-11-09T12:00:00Z"));
    DeliveryProperties props = new DeliveryProperties();
    OfferRepository offers;
    AdServingService serving;
    EventService events;
    FrequencyPolicy frequency;
    MatchOutcome nextOutcome;

    @BeforeAll
    static void setUpDb() {
        var ds = new DriverManagerDataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
        Flyway.configure().dataSource(ds).load().migrate();
        jdbc = JdbcClient.create(ds);
        var repo = new JdbcCampaignRepository(jdbc);
        var service = new CampaignService(repo, new PgIntentIndex(jdbc), new HashingTextEmbedder(384),
                new EnrichmentService(null), TaxonomyProvider::fromMigration, new CreativeValidator(), Clock.systemUTC());
        loan = service.importActive(SyntheticCatalog.baseProducts().getFirst(), "it");
        loanQuestion = repo.creatives(loan.id()).stream().filter(c -> c.type() == AdFormat.QUESTION).findFirst().orElseThrow();
    }

    @BeforeEach
    void setUp() {
        offers = new OfferRepository(jdbc, JsonMapper.builder().build());
        var tokens = new OfferTokenService("it-secret");
        var eligible = new EligibleSetService(new JdbcCampaignRepository(jdbc), new BlacklistSegmentFilter(),
                new MockClientSegmentsProvider(Map.of(), List.of(), 0), clock, Duration.ofMinutes(15));
        serving = new AdServingService((turn, deadline) -> nextOutcome, offers, tokens, eligible, props, clock);
        events = new EventService(offers, tokens, props, clock);
        frequency = new FrequencyPolicy(offers, props, clock);
        nextOutcome = new MatchOutcome(Optional.of(new Selection(loan, loanQuestion, AdFormat.QUESTION, 0.9, 0.63,
                "Вы обсуждаете бюджет ремонта")), null, null, List.of(), Map.of("total", 5L), Map.of(), false);
    }

    @Test
    void issuedOfferBecomesShownOnlyAfterImpression() {
        String session = "s-impression";
        AdOfferView view = serving.serve(request(session, 1)).orElseThrow();
        OfferRecord issued = offers.bySession(session).getFirst();
        assertThat(issued.status()).isEqualTo(OfferStatus.ISSUED);
        assertThat(view.label()).isEqualTo("Реклама");
        assertThat(view.creative().questionText()).isEqualTo(loanQuestion.questionText());

        assertThat(events.record(event(view, EventType.IMPRESSION)).result()).isEqualTo(Result.ACCEPTED);
        assertThat(events.record(event(view, EventType.IMPRESSION)).result()).isEqualTo(Result.DUPLICATE);
        assertThat(offers.find(issued.id()).orElseThrow().status()).isEqualTo(OfferStatus.SHOWN);
    }

    @Test
    void frequencyCountsPendingOffersAndShownCampaigns() {
        String session = "s-frequency";
        serving.serve(request(session, 1)).orElseThrow();

        assertThat(frequency.check(session, 2)).contains(NoAdReason.GATE_FREQUENCY);
        assertThat(frequency.check(session, 4)).isEmpty();
        assertThat(frequency.usedCampaigns(session)).containsExactly(loan.id());
    }

    @Test
    void sessionLimitStopsAds() {
        String session = "s-limit";
        for (int turn = 1; turn <= 7; turn += 3) {
            serving.serve(request(session, turn)).orElseThrow();
        }
        assertThat(frequency.check(session, 20)).contains(NoAdReason.GATE_SESSION_LIMIT);
    }

    @Test
    void clickWithoutImpressionCountsAsImplicitImpression() {
        String session = "s-click";
        AdOfferView view = serving.serve(request(session, 1)).orElseThrow();
        assertThat(events.record(event(view, EventType.QUESTION_CLICK)).result()).isEqualTo(Result.ACCEPTED);
        OfferRecord o = offers.bySession(session).getFirst();
        assertThat(o.status()).isEqualTo(OfferStatus.SHOWN);
        assertThat(offers.eventTypes(o.id())).containsExactly("IMPRESSION", "QUESTION_CLICK");
    }

    @Test
    void dismissSuppressesCampaignTopics() {
        String session = "s-dismiss";
        AdOfferView view = serving.serve(request(session, 1)).orElseThrow();
        events.record(event(view, EventType.IMPRESSION));
        events.record(event(view, EventType.DISMISS));
        assertThat(frequency.suppressedTopics(session)).containsExactlyInAnyOrderElementsOf(loan.topics());
    }

    @Test
    void offerExpiresWithoutImpression() {
        String session = "s-expire";
        AdOfferView view = serving.serve(request(session, 1)).orElseThrow();
        clock.advance(props.getOfferTtl().plusSeconds(1));

        assertThat(events.record(event(view, EventType.IMPRESSION)).result()).isEqualTo(Result.REJECTED);
        offers.expire(clock.instant());
        assertThat(offers.bySession(session).getFirst().status()).isEqualTo(OfferStatus.EXPIRED);
        assertThat(frequency.usedCampaigns(session)).isEmpty();
    }

    @Test
    void noAdDecisionIsLoggedButNotReturned() {
        String session = "s-noad";
        Trace trace = new Trace();
        nextOutcome = MatchOutcome.noAd(NoAdReason.SENSITIVE_TOPIC, null, List.of(), trace, false);
        assertThat(serving.serve(request(session, 1))).isEmpty();
        OfferRecord o = offers.bySession(session).getFirst();
        assertThat(o.status()).isEqualTo(OfferStatus.NO_AD);
        assertThat(o.noAdReason()).isEqualTo("SENSITIVE_TOPIC");
    }

    @Test
    void forgedTokenAndConversionAreRejected() {
        AdOfferView view = serving.serve(request("s-forged", 1)).orElseThrow();
        assertThat(events.record(new EventRequest(view.offerToken() + "x", EventType.IMPRESSION, null, "web")).result())
                .isEqualTo(Result.REJECTED);
        assertThat(events.record(event(view, EventType.CONVERSION)).result()).isEqualTo(Result.REJECTED);
    }

    private MatchRequest request(String session, int turn) {
        return new MatchRequest(session, "client-1", turn, List.of(DialogMessage.client("Хочу ремонт")), null);
    }

    private EventRequest event(AdOfferView view, EventType type) {
        return new EventRequest(view.offerToken(), type, clock.instant(), "web");
    }

    static class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
