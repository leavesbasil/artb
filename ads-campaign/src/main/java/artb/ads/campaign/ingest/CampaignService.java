package artb.ads.campaign.ingest;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import artb.ads.campaign.model.AdFormat;
import artb.ads.campaign.model.Campaign;
import artb.ads.campaign.model.CampaignIntent;
import artb.ads.campaign.model.CampaignStatus;
import artb.ads.campaign.model.Creative;
import artb.ads.campaign.model.IntentPolarity;
import artb.ads.campaign.model.TaxonomyProvider;
import artb.ads.campaign.store.CampaignRepository;
import artb.ads.campaign.store.IntentIndex;
import artb.ads.campaign.store.IntentIndex.IndexedIntent;
import artb.ads.llm.embedding.TextEmbedder;

/**
 * Жизненный цикл кампании: черновик → обогащение LLM → ревью менеджером → ACTIVE.
 * Любая правка описания возвращает кампанию на ревью (через повторное создание черновика).
 */
public class CampaignService {

    public record CreateRequest(
            String externalKey,
            String name,
            String businessDescription,
            Integer priority,
            Instant startAt,
            Instant endAt,
            List<String> excludedSegments,
            List<String> excludedTopics,
            CampaignDefinition.CardSpec card) {}

    public record ApproveRequest(String approver, Set<UUID> rejectIntentIds, Set<UUID> rejectCreativeIds) {}

    private final CampaignRepository repo;
    private final IntentIndex index;
    private final TextEmbedder embedder;
    private final EnrichmentService enrichment;
    private final TaxonomyProvider taxonomy;
    private final CreativeValidator validator;
    private final Clock clock;

    public CampaignService(CampaignRepository repo, IntentIndex index, TextEmbedder embedder, EnrichmentService enrichment,
            TaxonomyProvider taxonomy, CreativeValidator validator, Clock clock) {
        this.repo = repo;
        this.index = index;
        this.embedder = embedder;
        this.enrichment = enrichment;
        this.taxonomy = taxonomy;
        this.validator = validator;
        this.clock = clock;
    }

    /** Создаёт черновик с карточкой и запускает обогащение. Итог — кампания в статусе REVIEW. */
    public Campaign createAndEnrich(CreateRequest req) {
        Instant now = clock.instant();
        Campaign draft = new Campaign(UUID.randomUUID(), req.externalKey(), req.name(), CampaignStatus.DRAFT,
                req.businessDescription(), List.of(), req.priority() == null ? 50 : req.priority(), req.startAt(), req.endAt(),
                nz(req.excludedSegments()), nz(req.excludedTopics()), 0, now);
        List<Creative> cards = new ArrayList<>();
        if (req.card() != null) {
            Creative card = card(draft.id(), req.card());
            List<String> errors = validator.validate(card);
            if (!errors.isEmpty()) {
                throw new ValidationException(errors);
            }
            cards.add(card);
        }
        repo.save(draft);
        repo.saveCreatives(cards);

        EnrichmentService.EnrichmentResult r = enrichment.enrich(draft, cards, taxonomy.taxonomy());
        List<CampaignIntent> intents = new ArrayList<>();
        r.positiveIntents().forEach(t -> intents.add(CampaignIntent.draft(draft.id(), t, IntentPolarity.POSITIVE)));
        r.negativeIntents().forEach(t -> intents.add(CampaignIntent.draft(draft.id(), t, IntentPolarity.NEGATIVE)));
        repo.saveIntents(intents);
        repo.saveCreatives(r.questions().stream().map(q -> Creative.question(draft.id(), q)).toList());

        Campaign review = new Campaign(draft.id(), draft.externalKey(), draft.name(), CampaignStatus.REVIEW,
                draft.businessDescription(), r.topics(), draft.priority(), draft.startAt(), draft.endAt(),
                draft.excludedSegments(), draft.excludedTopics(), 0, clock.instant());
        repo.save(review);
        return review;
    }

    /** Одобряет всё, кроме отклонённого, считает эмбеддинги и включает кампанию. */
    public Campaign approve(UUID campaignId, ApproveRequest req) {
        Campaign c = repo.find(campaignId).orElseThrow(() -> new NotFoundException("кампания " + campaignId));
        Instant now = clock.instant();
        Set<UUID> rejectIntents = req.rejectIntentIds() == null ? Set.of() : req.rejectIntentIds();
        Set<UUID> rejectCreatives = req.rejectCreativeIds() == null ? Set.of() : req.rejectCreativeIds();

        List<Creative> approvedCreatives = repo.creatives(campaignId).stream()
                .filter(cr -> !rejectCreatives.contains(cr.id()))
                .map(cr -> cr.approved() ? cr : cr.approve(req.approver(), now))
                .toList();
        List<String> errors = new ArrayList<>();
        approvedCreatives.forEach(cr -> validator.validate(cr).forEach(e -> errors.add(label(cr) + ": " + e)));
        List<CampaignIntent> approvedIntents = repo.intents(campaignId).stream()
                .filter(i -> !rejectIntents.contains(i.id()))
                .map(CampaignIntent::approve)
                .toList();
        if (approvedCreatives.isEmpty()) {
            errors.add("нет ни одного одобренного креатива");
        }
        if (approvedIntents.stream().noneMatch(i -> i.polarity() == IntentPolarity.POSITIVE)) {
            errors.add("нет ни одного одобренного позитивного интента");
        }
        if (!errors.isEmpty()) {
            throw new ValidationException(errors);
        }
        repo.saveCreatives(approvedCreatives);
        repo.saveIntents(approvedIntents);
        reindex(campaignId, approvedIntents);
        Campaign active = c.withStatus(CampaignStatus.ACTIVE, repo.nextCatalogVersion(), now);
        repo.save(active);
        return active;
    }

    public Campaign pause(UUID campaignId) {
        Campaign c = repo.find(campaignId).orElseThrow(() -> new NotFoundException("кампания " + campaignId));
        Campaign paused = c.withStatus(CampaignStatus.PAUSED, repo.nextCatalogVersion(), clock.instant());
        repo.save(paused);
        return paused;
    }

    /** Импорт готовой кампании сразу в ACTIVE (синтетический каталог, golden set). */
    public Campaign importActive(CampaignDefinition def, String approver) {
        Instant now = clock.instant();
        UUID id = repo.findByExternalKey(def.key()).map(Campaign::id).orElseGet(UUID::randomUUID);
        List<Creative> creatives = new ArrayList<>();
        if (def.card() != null) {
            creatives.add(card(id, def.card()).approve(approver, now));
        }
        def.questions().forEach(q -> creatives.add(Creative.question(id, q).approve(approver, now)));
        List<String> errors = new ArrayList<>();
        creatives.forEach(cr -> validator.validate(cr).forEach(e -> errors.add(def.key() + " / " + label(cr) + ": " + e)));
        if (!errors.isEmpty()) {
            throw new ValidationException(errors);
        }
        List<CampaignIntent> intents = new ArrayList<>();
        def.positiveIntents().forEach(t -> intents.add(new CampaignIntent(UUID.randomUUID(), id, t, IntentPolarity.POSITIVE, true)));
        def.negativeIntents().forEach(t -> intents.add(new CampaignIntent(UUID.randomUUID(), id, t, IntentPolarity.NEGATIVE, true)));

        Campaign campaign = new Campaign(id, def.key(), def.name(), CampaignStatus.ACTIVE, def.description(), def.topics(),
                def.priority() == null ? 50 : def.priority(), def.startAt(), def.endAt(), def.excludedSegments(),
                def.excludedTopics(), repo.nextCatalogVersion(), now);
        repo.save(campaign);
        repo.saveCreatives(creatives);
        repo.saveIntents(intents);
        reindex(id, intents);
        return campaign;
    }

    private void reindex(UUID campaignId, Collection<CampaignIntent> intents) {
        List<CampaignIntent> list = List.copyOf(intents);
        List<float[]> vectors = embedder.embedAll(list.stream().map(CampaignIntent::text).toList());
        List<IndexedIntent> indexed = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            CampaignIntent ci = list.get(i);
            indexed.add(new IndexedIntent(ci.id(), ci.text(), ci.polarity(), vectors.get(i)));
        }
        index.index(campaignId, embedder.modelId(), indexed);
    }

    private static Creative card(UUID campaignId, CampaignDefinition.CardSpec s) {
        return Creative.card(campaignId, s.title(), s.body(), s.imageUrl(), s.cta(), s.url(), s.disclaimer(), s.erid());
    }

    private static String label(Creative c) {
        return c.type() == AdFormat.CARD ? "карточка «" + c.title() + "»" : "вопрос «" + c.questionText() + "»";
    }

    private static List<String> nz(List<String> l) {
        return l == null ? List.of() : l;
    }

    public static class ValidationException extends RuntimeException {
        private final List<String> errors;

        public ValidationException(List<String> errors) {
            super(String.join("; ", errors));
            this.errors = List.copyOf(errors);
        }

        public List<String> errors() {
            return errors;
        }
    }

    public static class NotFoundException extends RuntimeException {
        public NotFoundException(String what) {
            super("Не найдено: " + what);
        }
    }
}
