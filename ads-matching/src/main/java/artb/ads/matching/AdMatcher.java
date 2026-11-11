package artb.ads.matching;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import artb.ads.campaign.model.Campaign;
import artb.ads.campaign.model.Creative;
import artb.ads.campaign.model.TaxonomyProvider;
import artb.ads.campaign.model.TopicTaxonomy;
import artb.ads.campaign.store.CampaignRepository;
import artb.ads.matching.CandidateRetriever.Retrieved;
import artb.ads.matching.MatchOutcome.CandidateTrace;
import artb.ads.matching.MatchOutcome.Selection;
import artb.ads.matching.llm.IntentResult;
import artb.ads.ranking.CandidateRanker;
import artb.ads.ranking.JudgedCandidate;
import artb.ads.ranking.RankedCandidate;
import artb.ads.ranking.RankingContext;
import artb.ads.targeting.EligibleSet;
import artb.ads.targeting.EligibleSetService;

/**
 * Подбор на ходе: гейт → интент и темы → retrieval → LLM-судья → ранжирование.
 * Каждый этап может завершить подбор с причиной «без рекламы». Подбор видит только текст диалога
 * и уже допущенные кампании; сегменты клиента остаются в eligible set.
 */
public class AdMatcher implements TurnMatcher {

    private static final Logger log = LoggerFactory.getLogger(AdMatcher.class);
    private static final ExecutorService EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

    private final EligibleSetService eligibleSets;
    private final Gate gate;
    private final IntentExtractor intents;
    private final CandidateRetriever retriever;
    private final Judge judge;
    private final CandidateRanker ranker;
    private final FrequencyGuard frequency;
    private final CatalogSnapshot catalog;
    private final CampaignRepository repo;
    private final TaxonomyProvider taxonomy;
    private final SessionCache cache;
    private final MatchingProperties props;
    private final Clock clock;

    public AdMatcher(EligibleSetService eligibleSets, Gate gate, IntentExtractor intents, CandidateRetriever retriever,
            Judge judge, CandidateRanker ranker, FrequencyGuard frequency, CatalogSnapshot catalog,
            CampaignRepository repo, TaxonomyProvider taxonomy, SessionCache cache, MatchingProperties props, Clock clock) {
        this.eligibleSets = eligibleSets;
        this.gate = gate;
        this.intents = intents;
        this.retriever = retriever;
        this.judge = judge;
        this.ranker = ranker;
        this.frequency = frequency;
        this.catalog = catalog;
        this.repo = repo;
        this.taxonomy = taxonomy;
        this.cache = cache;
        this.props = props;
        this.clock = clock;
    }

    @Override
    public MatchOutcome match(TurnRequest turn, Duration deadline) {
        return matchWithDeadline(turn, deadline);
    }

    /** Подбор с дедлайном: не уложился — {@link NoAdReason#TIMEOUT}, ответ агента не ждёт рекламу. */
    public MatchOutcome matchWithDeadline(TurnRequest turn, Duration deadline) {
        if (!props.isDeadlineEnabled()) {
            try {
                return match(turn);
            } catch (RuntimeException e) {
                // Ошибка подбора не должна ломать ход: клиент получает ответ агента без рекламы.
                log.warn("Подбор упал на ходе {} сессии {}", turn.turnNo(), turn.sessionId(), e);
                return MatchOutcome.noAd(NoAdReason.ERROR, null, List.of(), new Trace(), false);
            }
        }
        // Трасса общая с потоком подбора: при дедлайне в ней уже есть пройденные этапы — видно, где застрял ход.
        Trace trace = new Trace();
        Future<MatchOutcome> future = EXECUTOR.submit(() -> match(turn, trace));
        try {
            return future.get(deadline.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            log.warn("Подбор не уложился в {} мс на ходе {} сессии {}; пройденные этапы: {}",
                    deadline.toMillis(), turn.turnNo(), turn.sessionId(), trace.latencyMs());
            trace.stage("total", deadline.toMillis());
            return MatchOutcome.noAd(NoAdReason.TIMEOUT, null, List.of(), trace, false);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return MatchOutcome.noAd(NoAdReason.ERROR, null, List.of(), new Trace(), false);
        } catch (ExecutionException e) {
            log.warn("Подбор упал на ходе {} сессии {}", turn.turnNo(), turn.sessionId(), e.getCause());
            return MatchOutcome.noAd(NoAdReason.ERROR, null, List.of(), new Trace(), false);
        }
    }

    public MatchOutcome match(TurnRequest turn) {
        return match(turn, new Trace());
    }

    private MatchOutcome match(TurnRequest turn, Trace trace) {
        long started = System.nanoTime();
        MatchOutcome outcome = doMatch(turn, trace);
        trace.stage("total", (System.nanoTime() - started) / 1_000_000);
        return outcome.withLatency(trace.latencyMs());
    }

    private MatchOutcome doMatch(TurnRequest turn, Trace trace) {
        EligibleSet eligible = eligibleSets.forSession(turn.sessionId(), turn.clientId());
        if (eligible.campaignIds().isEmpty()) {
            return MatchOutcome.noAd(NoAdReason.NO_ELIGIBLE, null, List.of(), trace, false);
        }
        Optional<NoAdReason> gated = gate.check(turn, trace);
        if (gated.isPresent()) {
            return MatchOutcome.noAd(gated.get(), null, List.of(), trace, false);
        }

        TopicTaxonomy tx = taxonomy.taxonomy();
        IntentResult intent = intents.extract(turn, tx, trace);
        boolean sensitive = intent.topics().stream()
                .anyMatch(t -> tx.isSensitive(t.id()) && t.confidence() >= props.getSensitiveTopicConfidence());
        if (sensitive) {
            return MatchOutcome.noAd(NoAdReason.SENSITIVE_TOPIC, intent, List.of(), trace, false);
        }
        if (intent.queries().isEmpty() || intent.commercialIntent() < props.getCommercialIntentThreshold()) {
            return MatchOutcome.noAd(NoAdReason.LOW_INTENT, intent, List.of(), trace, false);
        }
        Set<String> dialogTopics = intent.topics().stream()
                .filter(t -> t.confidence() >= props.getTopicConfidence())
                .map(IntentResult.TopicScore::id)
                .collect(Collectors.toUnmodifiableSet());

        Map<UUID, Campaign> live = catalog.live();
        Set<UUID> used = frequency.usedCampaigns(turn.sessionId());
        Set<String> suppressed = frequency.suppressedTopics(turn.sessionId());
        List<Campaign> pool = eligible.campaignIds().stream()
                .map(live::get)
                .filter(c -> c != null && !used.contains(c.id()))
                .filter(c -> Collections.disjoint(c.excludedTopics(), dialogTopics))
                .filter(c -> Collections.disjoint(c.topics(), suppressed))
                .toList();

        long t0 = System.nanoTime();
        List<float[]> vectors = retriever.embed(intent.queries());
        float[] centroid = SessionCache.centroid(vectors);
        SessionCache.Entry cached = cache.find(turn.sessionId(), dialogTopics, centroid, eligible.catalogVersion(),
                props.getCacheQuerySimilarity());
        boolean fromCache = cached != null;
        List<Retrieved> retrieved;
        List<JudgedCandidate> judged;
        Set<UUID> poolIds = pool.stream().map(Campaign::id).collect(Collectors.toSet());
        if (fromCache) {
            retrieved = cached.retrieved().stream().filter(r -> poolIds.contains(r.campaignId())).toList();
            judged = cached.judged().stream().filter(j -> poolIds.contains(j.campaignId())).toList();
            trace.stage("retrieval", (System.nanoTime() - t0) / 1_000_000);
        } else {
            retrieved = retriever.retrieve(vectors, pool, dialogTopics);
            trace.stage("retrieval", (System.nanoTime() - t0) / 1_000_000);
            if (retrieved.isEmpty()) {
                cache.put(turn.sessionId(), new SessionCache.Entry(dialogTopics, centroid, eligible.catalogVersion(), List.of(), List.of()));
                return MatchOutcome.noAd(NoAdReason.NO_CANDIDATES, intent, List.of(), trace, false);
            }
            // Судье — только лучшие по сходству: время судьи растёт с числом кандидатов (каждому — оценка и причина).
            List<Retrieved> toJudge = retrieved.subList(0, Math.min(retrieved.size(), props.getJudgeTopK()));
            Map<UUID, List<Creative>> creatives = repo.approvedCreatives(toJudge.stream().map(Retrieved::campaignId).toList());
            judged = judge.judge(turn, toJudge, live, creatives, trace);
            cache.put(turn.sessionId(), new SessionCache.Entry(dialogTopics, centroid, eligible.catalogVersion(), retrieved, judged));
        }
        if (retrieved.isEmpty()) {
            return MatchOutcome.noAd(NoAdReason.NO_CANDIDATES, intent, List.of(), trace, true);
        }

        List<RankedCandidate> ranked = ranker.rank(judged, new RankingContext(turn.sessionId(), turn.turnNo(), clock.instant()));
        List<CandidateTrace> traces = traces(retrieved, judged, ranked, live);
        if (ranked.isEmpty()) {
            return MatchOutcome.noAd(NoAdReason.LOW_RELEVANCE, intent, traces, trace, fromCache);
        }
        RankedCandidate top = ranked.getFirst();
        JudgedCandidate j = top.candidate();
        Campaign campaign = live.get(j.campaignId());
        Creative creative = repo.approvedCreatives(List.of(j.campaignId())).getOrDefault(j.campaignId(), List.of()).stream()
                .filter(cr -> cr.id().equals(j.creativeId())).findFirst().orElse(null);
        if (campaign == null || creative == null) {
            return MatchOutcome.noAd(NoAdReason.NO_CANDIDATES, intent, traces, trace, fromCache);
        }
        Selection selection = new Selection(campaign, creative, j.format(), j.relevance(), top.score(), j.reason());
        return new MatchOutcome(Optional.of(selection), null, intent, traces, trace.latencyMs(), trace.models(), fromCache);
    }

    private static List<CandidateTrace> traces(List<Retrieved> retrieved, List<JudgedCandidate> judged,
            List<RankedCandidate> ranked, Map<UUID, Campaign> live) {
        Map<UUID, JudgedCandidate> byId = new HashMap<>();
        judged.forEach(j -> byId.put(j.campaignId(), j));
        Map<UUID, Double> scores = new HashMap<>();
        ranked.forEach(r -> scores.put(r.candidate().campaignId(), r.score()));
        List<CandidateTrace> result = new ArrayList<>();
        for (Retrieved r : retrieved) {
            JudgedCandidate j = byId.get(r.campaignId());
            Campaign c = live.get(r.campaignId());
            result.add(new CandidateTrace(r.campaignId(), c == null ? "?" : c.name(), r.similarity(),
                    j == null ? null : j.relevance(), scores.get(r.campaignId()), j == null ? null : j.reason()));
        }
        result.sort((a, b) -> Double.compare(nz(b.score()), nz(a.score())));
        return result;
    }

    private static double nz(Double d) {
        return d == null ? -1 : d;
    }
}
