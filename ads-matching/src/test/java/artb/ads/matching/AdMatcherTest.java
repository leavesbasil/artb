package artb.ads.matching;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import artb.ads.campaign.ingest.CampaignService;
import artb.ads.campaign.ingest.CreativeValidator;
import artb.ads.campaign.ingest.EnrichmentService;
import artb.ads.campaign.model.AdFormat;
import artb.ads.campaign.model.Campaign;
import artb.ads.campaign.model.TaxonomyProvider;
import artb.ads.campaign.model.TopicTaxonomy;
import artb.ads.campaign.store.InMemoryCampaignRepository;
import artb.ads.campaign.store.InMemoryIntentIndex;
import artb.ads.campaign.synthetic.SyntheticCatalog;
import artb.ads.llm.LlmStage;
import artb.ads.llm.embedding.HashingTextEmbedder;
import artb.ads.matching.llm.GateVerdict;
import artb.ads.matching.llm.IntentResult;
import artb.ads.matching.llm.IntentResult.TopicScore;
import artb.ads.matching.llm.JudgeResult;
import artb.ads.matching.llm.JudgeResult.Judgement;
import artb.ads.ranking.RelevancePriorityRanker;
import artb.ads.targeting.BlacklistSegmentFilter;
import artb.ads.targeting.EligibleSetService;
import artb.ads.targeting.MockClientSegmentsProvider;

class AdMatcherTest {

    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-09T12:00:00Z"), ZoneOffset.UTC);
    static final TopicTaxonomy TAXONOMY = TaxonomyProvider.fromMigration();
    static final Pattern CANDIDATE = Pattern.compile("(?m)^(c\\d+): (.+)$");

    InMemoryCampaignRepository repo;
    InMemoryIntentIndex index;
    HashingTextEmbedder embedder = new HashingTextEmbedder(384);
    MatchingProperties props = new MatchingProperties();
    ScriptedLlm llm;
    FakeFrequency frequency;
    Map<String, Campaign> byKey;

    @BeforeEach
    void setUp() {
        repo = new InMemoryCampaignRepository();
        index = new InMemoryIntentIndex();
        CampaignService service = new CampaignService(repo, index, embedder, new EnrichmentService(null),
                () -> TAXONOMY, new CreativeValidator(), CLOCK);
        byKey = new java.util.HashMap<>();
        SyntheticCatalog.baseProducts().forEach(d -> byKey.put(d.key(), service.importActive(d, "test")));
        frequency = new FakeFrequency();
        llm = new ScriptedLlm()
                .on(LlmStage.GATE, u -> new GateVerdict(false, "спокойный разговор"))
                .on(LlmStage.JUDGE, AdMatcherTest::judgeFavoringLoan);
    }

    @Test
    void renovationTalkLeadsToLoanQuestion() {
        llm.on(LlmStage.INTENT, u -> renovationIntent());

        MatchOutcome out = matcher(Map.of()).match(turn("s1", "c-1", 1, "Хочу сделать ремонт на кухне, своих денег не хватает"));

        assertThat(out.selection()).isPresent();
        assertThat(out.selection().get().campaign().externalKey()).isEqualTo("consumer-loan");
        assertThat(out.selection().get().format()).isEqualTo(AdFormat.QUESTION);
        assertThat(out.selection().get().creative().questionText()).isNotBlank();
        assertThat(out.candidates()).isNotEmpty();
        assertThat(out.latencyMs()).containsKeys("gate", "intent", "retrieval", "judge", "total");
    }

    @Test
    void sensitiveTopicStopsBeforeRetrieval() {
        llm.on(LlmStage.INTENT, u -> new IntentResult(List.of("клиенту нечем платить по кредиту"), 0.6,
                List.of(new TopicScore("financial_hardship", 0.35), new TopicScore("consumer_loan", 0.8))));

        MatchOutcome out = matcher(Map.of()).match(turn("s2", "c-1", 1, "Мне нечем платить кредит, что делать?"));

        assertThat(out.noAdReason()).isEqualTo(NoAdReason.SENSITIVE_TOPIC);
        assertThat(llm.calls(LlmStage.JUDGE)).isZero();
    }

    @Test
    void lowCommercialIntentGivesNoAd() {
        llm.on(LlmStage.INTENT, u -> new IntentResult(List.of("клиент благодарит"), 0.1, List.of(new TopicScore("general", 0.9))));
        assertThat(matcher(Map.of()).match(turn("s3", "c-1", 1, "Спасибо!")).noAdReason()).isEqualTo(NoAdReason.LOW_INTENT);
    }

    @Test
    void gateStopsWhenClientIsSolvingProblem() {
        llm.on(LlmStage.GATE, u -> new GateVerdict(true, "клиент решает проблему с платежом"));
        MatchOutcome out = matcher(Map.of()).match(turn("s4", "c-1", 1, "Не проходит оплата картой"));
        assertThat(out.noAdReason()).isEqualTo(NoAdReason.GATE_DIALOG_STAGE);
        assertThat(llm.calls(LlmStage.INTENT)).isZero();
    }

    @Test
    void frequencyRuleStopsWithoutCallingModels() {
        frequency.reason = Optional.of(NoAdReason.GATE_FREQUENCY);
        MatchOutcome out = matcher(Map.of()).match(turn("s5", "c-1", 2, "Хочу ремонт"));
        assertThat(out.noAdReason()).isEqualTo(NoAdReason.GATE_FREQUENCY);
        assertThat(llm.calls(LlmStage.GATE)).isZero();
    }

    @Test
    void sameTopicOnNextTurnReusesJudge() {
        llm.on(LlmStage.INTENT, u -> renovationIntent());
        AdMatcher matcher = matcher(Map.of());
        matcher.match(turn("s6", "c-1", 1, "Хочу сделать ремонт на кухне"));
        MatchOutcome second = matcher.match(turn("s6", "c-1", 2, "Ремонт кухни, бюджет пока не знаю"));

        assertThat(second.fromCache()).isTrue();
        assertThat(llm.calls(LlmStage.JUDGE)).isEqualTo(1);
    }

    @Test
    void usedCampaignIsNotShownAgain() {
        llm.on(LlmStage.INTENT, u -> renovationIntent());
        frequency.used = Set.of(byKey.get("consumer-loan").id());
        MatchOutcome out = matcher(Map.of()).match(turn("s7", "c-1", 3, "Хочу сделать ремонт на кухне"));
        assertThat(out.selection().map(s -> s.campaign().externalKey())).isNotEqualTo(Optional.of("consumer-loan"));
    }

    @Test
    void blacklistedSegmentExcludesCampaign() {
        llm.on(LlmStage.INTENT, u -> renovationIntent());
        MatchOutcome out = matcher(Map.of("risky", List.of("bankruptcy_risk")))
                .match(turn("s8", "risky", 1, "Хочу сделать ремонт на кухне"));
        assertThat(out.candidates()).extracting(MatchOutcome.CandidateTrace::name).doesNotContain("Кредит наличными");
    }

    @Test
    void negativeIntentDropsCampaignInRetrieval() {
        var retriever = new CandidateRetriever(index, embedder, props);
        List<Campaign> pool = List.of(byKey.get("consumer-loan"));
        var vectors = retriever.embed(List.of("клиенту нечем платить по кредиту и он просит отсрочку"));
        assertThat(retriever.retrieve(vectors, pool, Set.of("consumer_loan"))).isEmpty();
        var positive = retriever.embed(List.of("клиент планирует ремонт квартиры и прикидывает бюджет"));
        assertThat(retriever.retrieve(positive, pool, Set.of("consumer_loan"))).hasSize(1);
    }

    @Test
    void lexicallyCloseNegativeIntentDoesNotHidePositiveMatch() {
        // «закрыть кредитную карту» лексически так же близко, как «оформить» — запас не даёт потерять кампанию
        var retriever = new CandidateRetriever(index, embedder, props);
        var vectors = retriever.embed(List.of("клиент ищет кредитную карту с длинным льготным периодом"));
        assertThat(retriever.retrieve(vectors, List.of(byKey.get("credit-card")), Set.of("credit_card")))
                .extracting(CandidateRetriever.Retrieved::campaignId).containsExactly(byKey.get("credit-card").id());
    }

    @Test
    void modelFailureBecomesNoAdInsteadOfBreakingTheTurn() {
        llm.on(LlmStage.INTENT, u -> { throw new artb.ads.llm.LlmException("бюджет исчерпан"); });
        props.setDeadlineEnabled(false);
        MatchOutcome out = matcher(Map.of()).matchWithDeadline(turn("s10", "c-1", 1, "ремонт"), Duration.ofSeconds(1));
        assertThat(out.noAdReason()).isEqualTo(NoAdReason.ERROR);
    }

    @Test
    void slowMatchingTimesOut() {
        llm.on(LlmStage.INTENT, u -> {
            sleep(500);
            return renovationIntent();
        });
        props.setDeadlineEnabled(true);
        MatchOutcome out = matcher(Map.of()).matchWithDeadline(turn("s9", "c-1", 1, "ремонт"), Duration.ofMillis(100));
        assertThat(out.noAdReason()).isEqualTo(NoAdReason.TIMEOUT);
    }

    // --- вспомогательное ---

    AdMatcher matcher(Map<String, List<String>> profiles) {
        var eligible = new EligibleSetService(repo, new BlacklistSegmentFilter(),
                new MockClientSegmentsProvider(profiles, List.of(), 0), CLOCK, Duration.ofMinutes(15));
        return new AdMatcher(eligible, new Gate(frequency, llm, props), new IntentExtractor(llm, props),
                new CandidateRetriever(index, embedder, props), new Judge(llm, props), new RelevancePriorityRanker(0.6),
                frequency, new CatalogSnapshot(repo, CLOCK), repo, () -> TAXONOMY, new SessionCache(), props, CLOCK);
    }

    static TurnRequest turn(String session, String client, int n, String text) {
        return new TurnRequest(session, client, n, List.of(DialogMessage.client(text)));
    }

    static IntentResult renovationIntent() {
        return new IntentResult(List.of("клиент планирует ремонт кухни и прикидывает бюджет",
                "клиенту не хватает своих денег на ремонт"), 0.8,
                List.of(new TopicScore("home_renovation", 0.9), new TopicScore("consumer_loan", 0.6)));
    }

    /** Судья-фейк: «Кредит наличными» релевантен, остальные — нет; формат QUESTION. */
    static JudgeResult judgeFavoringLoan(String user) {
        List<Judgement> list = new ArrayList<>();
        Matcher m = CANDIDATE.matcher(user);
        while (m.find()) {
            boolean loan = m.group(2).startsWith("Кредит наличными");
            list.add(new Judgement(m.group(1), loan ? 0.9 : 0.3, "QUESTION", m.group(1) + "-q1",
                    loan ? "Вы обсуждаете бюджет ремонта" : "не связано"));
        }
        return new JudgeResult(list);
    }

    static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    static class FakeFrequency implements FrequencyGuard {
        Optional<NoAdReason> reason = Optional.empty();
        Set<UUID> used = Set.of();

        @Override
        public Optional<NoAdReason> check(String sessionId, int turnNo) {
            return reason;
        }

        @Override
        public Set<UUID> usedCampaigns(String sessionId) {
            return used;
        }

        @Override
        public Set<String> suppressedTopics(String sessionId) {
            return Set.of();
        }
    }
}
