package artb.ads.matching;

import java.util.List;

import artb.ads.campaign.model.TopicTaxonomy;
import artb.ads.llm.LlmCall;
import artb.ads.llm.LlmGateway;
import artb.ads.llm.LlmStage;
import artb.ads.matching.llm.IntentResult;
import artb.ads.matching.llm.IntentResult.TopicScore;

/** Этап 2: запросы, коммерческий интент и темы диалога одним вызовом быстрой модели. */
public class IntentExtractor {

    static final String SYSTEM = """
            Ты анализируешь диалог клиента с ассистентом банка, чтобы подобрать уместное предложение банка.
            По последним репликам верни:

            queries — 1–3 коротких описания текущей потребности клиента от третьего лица, например
            «клиент планирует ремонт и прикидывает бюджет». Описывай ситуацию клиента, а не продукт банка.

            commercialIntent — число от 0 до 1: насколько сейчас уместно предложить клиенту продукт банка.
            0 — потребности нет (приветствие, благодарность, вопрос о работе приложения), 1 — клиент прямо
            ищет продукт. Скрытая потребность, которую закрывает продукт банка (ремонт → кредит, переезд →
            ипотека, страх залить соседей → страховка), — 0.4–0.7.

            topics — темы диалога из списка ниже с уверенностью от 0 до 1. Используй только id из списка.
            Стоп-темы отмечай всегда, даже при слабом сигнале.

            Темы:
            %s
            """;

    private final LlmGateway llm;
    private final MatchingProperties props;

    public IntentExtractor(LlmGateway llm, MatchingProperties props) {
        this.llm = llm;
        this.props = props;
    }

    public IntentResult extract(TurnRequest turn, TopicTaxonomy taxonomy, Trace trace) {
        LlmCall<IntentResult> call = llm.call(LlmStage.INTENT, SYSTEM.formatted(taxonomy.promptListing(true)),
                turn.transcript(props.getHistoryTurns()), IntentResult.class);
        trace.stage("intent", call);
        IntentResult raw = call.value();
        List<TopicScore> known = raw.topics().stream().filter(t -> t.id() != null && taxonomy.contains(t.id())).toList();
        List<String> queries = raw.queries().stream().map(String::strip).filter(q -> !q.isEmpty()).limit(3).toList();
        return new IntentResult(queries, Math.max(0, Math.min(1, raw.commercialIntent())), known);
    }
}
