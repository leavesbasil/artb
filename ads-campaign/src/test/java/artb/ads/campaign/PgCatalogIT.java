package artb.ads.campaign;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import artb.ads.campaign.ingest.CampaignService;
import artb.ads.campaign.model.Campaign;
import artb.ads.campaign.model.IntentPolarity;
import artb.ads.campaign.store.IntentIndex.IntentHit;
import artb.ads.campaign.store.JdbcCampaignRepository;
import artb.ads.campaign.store.PgIntentIndex;
import artb.ads.campaign.store.TopicRepository;
import artb.ads.campaign.synthetic.SyntheticCatalog;
import artb.ads.llm.embedding.HashingTextEmbedder;

/** Миграции, репозиторий и векторный поиск на настоящем Postgres + pgvector. */
@Testcontainers
class PgCatalogIT {

    @Container
    static final PostgreSQLContainer PG = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));

    static JdbcClient jdbc;

    @BeforeAll
    static void migrate() {
        var ds = new DriverManagerDataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
        Flyway.configure().dataSource(ds).load().migrate();
        jdbc = JdbcClient.create(ds);
    }

    @Test
    void importsCampaignAndFindsItByVector() {
        var repo = new JdbcCampaignRepository(jdbc);
        var index = new PgIntentIndex(jdbc);
        CampaignService service = TestCatalogs.service(repo, index);
        Campaign loan = service.importActive(SyntheticCatalog.baseProducts().getFirst(), "it");
        Campaign deposit = service.importActive(SyntheticCatalog.baseProducts().get(6), "it");

        var embedder = new HashingTextEmbedder(384);
        List<IntentHit> hits = index.search(embedder.embed("хочу сделать ремонт кухни, денег не хватает"),
                embedder.modelId(), List.of(loan.id(), deposit.id()), 5);

        assertThat(hits).isNotEmpty();
        assertThat(hits.getFirst().campaignId()).isEqualTo(loan.id());
        assertThat(hits.getFirst().polarity()).isEqualTo(IntentPolarity.POSITIVE);
        assertThat(repo.find(loan.id())).get().extracting(Campaign::topics).asList().contains("consumer_loan");
        assertThat(repo.approvedCreatives(List.of(loan.id())).get(loan.id())).hasSizeGreaterThan(1);
        assertThat(repo.findLive(TestCatalogs.CLOCK.instant())).extracting(Campaign::id).contains(loan.id(), deposit.id());
    }

    @Test
    void searchIgnoresOtherEmbeddingModelsAndForeignCampaigns() {
        var index = new PgIntentIndex(jdbc);
        var embedder = new HashingTextEmbedder(384);
        assertThat(index.search(embedder.embed("ремонт"), "other-model", List.of(UUID.randomUUID()), 5)).isEmpty();
    }

    @Test
    void taxonomyIsLoadedFromDatabase() {
        assertThat(new TopicRepository(jdbc).taxonomy().isSensitive("fraud")).isTrue();
    }
}
