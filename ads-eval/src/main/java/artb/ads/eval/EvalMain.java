package artb.ads.eval;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import artb.ads.campaign.ingest.CampaignService;
import artb.ads.campaign.ingest.CreativeValidator;
import artb.ads.campaign.ingest.EnrichmentService;
import artb.ads.campaign.model.Campaign;
import artb.ads.campaign.model.TaxonomyProvider;
import artb.ads.campaign.model.TopicTaxonomy;
import artb.ads.campaign.store.InMemoryCampaignRepository;
import artb.ads.campaign.store.InMemoryIntentIndex;
import artb.ads.campaign.synthetic.SyntheticCatalog;
import artb.ads.llm.DefaultLlmGateway;
import artb.ads.llm.LlmGateway;
import artb.ads.llm.LlmProperties;
import artb.ads.llm.LlmProperties.StageModel;
import artb.ads.llm.LlmStage;
import artb.ads.llm.StageModels;
import artb.ads.llm.embedding.HashingTextEmbedder;
import artb.ads.llm.embedding.SpringAiTextEmbedder;
import artb.ads.llm.embedding.TextEmbedder;
import artb.ads.llm.gigachat.GigaChatClients;
import artb.ads.llm.masking.RegexPiiMasker;
import artb.ads.matching.AdMatcher;
import artb.ads.matching.CandidateRetriever;
import artb.ads.matching.CatalogSnapshot;
import artb.ads.matching.FrequencyGuard;
import artb.ads.matching.Gate;
import artb.ads.matching.IntentExtractor;
import artb.ads.matching.Judge;
import artb.ads.matching.MatchOutcome;
import artb.ads.matching.MatchingProperties;
import artb.ads.matching.SessionCache;
import artb.ads.matching.TurnRequest;
import artb.ads.ranking.RelevancePriorityRanker;
import artb.ads.targeting.BlacklistSegmentFilter;
import artb.ads.targeting.EligibleSetService;
import artb.ads.targeting.MockClientSegmentsProvider;

/**
 * Прогон golden set без базы: базовый каталог в памяти, модели — GigaChat API (ключ в GIGACHAT_CREDENTIALS)
 * или любой OpenAI-совместимый сервер ({@code -Pprovider=internal -PbaseUrl=… -Pmodel=…}: gpt2giga, vLLM).
 * Каждый кейс — отдельная сессия без лимитов частоты. Ответы моделей пишутся в кассеты,
 * повторный прогон с теми же промптами бесплатный.
 */
public final class EvalMain {

    public static void main(String[] args) throws Exception {
        String provider = System.getProperty("eval.provider", "gigachat");
        boolean gigachat = provider.equals("gigachat");
        String baseUrl = System.getProperty("eval.baseUrl");
        String model = System.getProperty("eval.model", gigachat ? "GigaChat-2" : null);
        if (!gigachat && (baseUrl == null || model == null)) {
            throw new IllegalArgumentException("Для -Pprovider=internal нужны -PbaseUrl и -Pmodel");
        }
        String judgeModel = System.getProperty("eval.judgeModel", gigachat ? "GigaChat-2-Max" : model);
        String embedding = System.getProperty("eval.embedding", gigachat ? "EmbeddingsGigaR" : "hashing");
        // У ключа GigaChat физлица лимит одновременных запросов — по умолчанию последовательно.
        int parallelism = Integer.parseInt(System.getProperty("eval.parallelism", gigachat ? "1" : "4"));
        String recording = System.getProperty("eval.recording", "record");

        String only = System.getProperty("eval.only");

        LlmProperties llmProps = new LlmProperties();
        llmProps.setDefaultStage(new StageModel(provider, model));
        llmProps.getStages().put(LlmStage.JUDGE.key(), new StageModel(provider, judgeModel));
        if (gigachat) {
            configureGigaChat(llmProps.getGigachat(), baseUrl);
        } else {
            llmProps.getInternal().setBaseUrl(baseUrl);
            llmProps.getInternal().setTimeout(Duration.ofSeconds(120));
        }
        llmProps.getRecording().setMode(recording);
        llmProps.getRecording().setDir(Path.of("ads-eval/llm-cassettes"));
        GigaChatClients gigaChatClients = new GigaChatClients(llmProps.getGigachat());
        LlmGateway llm = new DefaultLlmGateway(new StageModels(llmProps, gigaChatClients), new RegexPiiMasker(), true);

        Clock clock = Clock.systemUTC();
        TopicTaxonomy taxonomy = TaxonomyProvider.fromMigration();
        var repo = new InMemoryCampaignRepository();
        var index = new InMemoryIntentIndex();
        TextEmbedder embedder = embedding.equals("hashing") ? new HashingTextEmbedder(384)
                : new SpringAiTextEmbedder(gigaChatClients.embeddingModel(embedding), "gigachat:" + embedding);
        var campaigns = new CampaignService(repo, index, embedder, new EnrichmentService(llm), () -> taxonomy,
                new CreativeValidator(), clock);
        Map<UUID, String> keyById = new HashMap<>();
        SyntheticCatalog.baseProducts().forEach(d -> {
            Campaign c = campaigns.importActive(d, "eval");
            keyById.put(c.id(), c.externalKey());
        });

        List<GoldenCase> cases = GoldenSet.load().stream().filter(c -> only == null || c.id().equals(only)).toList();
        Map<String, List<String>> profiles = new HashMap<>();
        cases.forEach(c -> profiles.put(c.id(), c.segmentsOrEmpty()));

        MatchingProperties props = new MatchingProperties();
        props.setDeadlineEnabled(false);
        var eligible = new EligibleSetService(repo, new BlacklistSegmentFilter(),
                new MockClientSegmentsProvider(profiles, List.of(), 0), clock, Duration.ofMinutes(15));
        AdMatcher matcher = new AdMatcher(eligible, new Gate(FrequencyGuard.NONE, llm, props), new IntentExtractor(llm, props),
                new CandidateRetriever(index, embedder, props), new Judge(llm, props), new RelevancePriorityRanker(0.6),
                FrequencyGuard.NONE, new CatalogSnapshot(repo, clock), repo, () -> taxonomy, new SessionCache(), props, clock);

        System.out.printf("Golden set: %d кейсов, %s, модели %s (судья %s), эмбеддинги %s, запись %s%n",
                cases.size(), gigachat ? "GigaChat API" : baseUrl, model, judgeModel, embedding, recording);
        ExecutorService pool = Executors.newFixedThreadPool(parallelism);
        List<Future<EvalResult>> futures = new ArrayList<>();
        for (GoldenCase gc : cases) {
            futures.add(pool.submit(() -> run(matcher, gc, keyById)));
        }
        List<EvalResult> results = new ArrayList<>();
        for (Future<EvalResult> f : futures) {
            EvalResult r = f.get();
            results.add(r);
            System.out.printf("  %s %-24s → %s%n", r.passed() ? "✓" : "✗", r.golden().id(),
                    r.error() != null ? "ошибка: " + r.error()
                            : r.selectedKey() != null ? r.selectedKey() + " " + r.format() : "нет: " + r.noAdReason());
        }
        pool.shutdown();

        Map<String, String> models = new LinkedHashMap<>();
        models.put("gate/intent", model);
        models.put("judge", judgeModel);
        models.put("embeddings", embedding);
        String report = EvalReport.render(results, models);
        Path out = Path.of("ads-eval/build/eval/report.md");
        Files.createDirectories(out.getParent());
        Files.writeString(out, report, StandardCharsets.UTF_8);
        System.out.println();
        System.out.println(report);
        System.out.println("Отчёт: " + out.toAbsolutePath());
    }

    /** Ключ и сертификат — из окружения, как у демо: GIGACHAT_CREDENTIALS, GIGACHAT_SCOPE, GIGACHAT_CA_CERTS. */
    private static void configureGigaChat(LlmProperties.GigaChat g, String baseUrl) {
        g.setCredentials(System.getenv("GIGACHAT_CREDENTIALS"));
        if (System.getenv("GIGACHAT_SCOPE") != null) {
            g.setScope(System.getenv("GIGACHAT_SCOPE"));
        }
        g.setCaCerts(System.getenv("GIGACHAT_CA_CERTS"));
        if (baseUrl != null) {
            g.setBaseUrl(baseUrl);
        }
        g.setReadTimeout(Duration.ofSeconds(120));
    }

    private static EvalResult run(AdMatcher matcher, GoldenCase gc, Map<UUID, String> keyById) {
        try {
            MatchOutcome out = matcher.match(new TurnRequest("eval-" + gc.id(), gc.id(), 1, gc.messages()));
            List<String> candidates = out.candidates().stream().map(c -> keyById.getOrDefault(c.campaignId(), "?")).toList();
            return new EvalResult(gc,
                    out.selection().map(s -> s.campaign().externalKey()).orElse(null),
                    out.selection().map(s -> s.format().name()).orElse(null),
                    out.noAdReason() == null ? null : out.noAdReason().name(),
                    candidates, out.latencyMs().getOrDefault("total", 0L), out.fromCache(), null);
        } catch (RuntimeException e) {
            return new EvalResult(gc, null, null, null, List.of(), 0, false,
                    e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }
}
