package artb.ads.campaign.ingest;

import java.util.List;

import artb.ads.campaign.model.Campaign;
import artb.ads.campaign.model.Creative;
import artb.ads.campaign.model.TopicTaxonomy;
import artb.ads.llm.LlmGateway;
import artb.ads.llm.LlmStage;

/** LLM предлагает черновики интентов, вопросов и тем по бизнес-описанию. В бой идёт только одобренное. */
public class EnrichmentService {

    public record EnrichmentResult(
            List<String> positiveIntents,
            List<String> negativeIntents,
            List<String> questions,
            List<String> topics) {}

    private static final String SYSTEM = """
            Ты помогаешь менеджеру банка подготовить рекламную кампанию собственного продукта банка к показу
            в диалоге клиента с ассистентом банка. По бизнес-описанию подготовь:

            positiveIntents — 10–20 коротких описаний ситуаций клиента от третьего лица, в которых продукт
            уместно предложить. Покрывай явные запросы («клиент выбирает кредит на машину») и скрытые
            потребности («клиент планирует ремонт и прикидывает бюджет»).

            negativeIntents — 5–8 ситуаций, близких по словам, но в которых показывать продукт нельзя:
            жалоба на этот же продукт, финансовые трудности, просрочка, мошенничество, спорные операции.

            questions — 5–10 вопросов от лица клиента (в первом лице), которые естественно продолжают
            разговор и ведут к продукту. Не придумывай ставки, суммы и условия, которых нет в описании.

            topics — 1–3 id тем из списка ниже, только из него. Стоп-темы не выбирай.

            Темы:
            %s
            """;

    private final LlmGateway llm;

    public EnrichmentService(LlmGateway llm) {
        this.llm = llm;
    }

    public EnrichmentResult enrich(Campaign campaign, List<Creative> cards, TopicTaxonomy taxonomy) {
        StringBuilder user = new StringBuilder()
                .append("Название кампании: ").append(campaign.name()).append('\n')
                .append("Бизнес-описание: ").append(campaign.businessDescription()).append('\n');
        cards.forEach(c -> user.append("Карточка: ").append(c.fullText()).append('\n'));
        EnrichmentResult raw = llm.call(LlmStage.INGESTION, SYSTEM.formatted(taxonomy.promptListing(false)),
                user.toString(), EnrichmentResult.class).value();
        List<String> topics = raw.topics() == null ? List.of()
                : raw.topics().stream().filter(t -> taxonomy.contains(t) && !taxonomy.isSensitive(t)).distinct().limit(3).toList();
        return new EnrichmentResult(clean(raw.positiveIntents()), clean(raw.negativeIntents()), clean(raw.questions()), topics);
    }

    private static List<String> clean(List<String> items) {
        return items == null ? List.of() : items.stream().map(String::strip).filter(s -> !s.isEmpty()).distinct().toList();
    }
}
