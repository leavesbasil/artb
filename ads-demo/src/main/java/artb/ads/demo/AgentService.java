package artb.ads.demo;

import java.util.List;

import org.springframework.stereotype.Service;

import artb.ads.campaign.model.Campaign;
import artb.ads.llm.LlmGateway;
import artb.ads.llm.LlmStage;
import artb.ads.matching.DialogMessage;
import artb.ads.matching.TurnRequest;

/**
 * Агент демо. О рекламе ничего не знает: подбор идёт параллельно и отдельно.
 * Исключение — клиент нажал рекламный вопрос: тогда агент отвечает о продукте строго по
 * одобренному описанию кампании (grounding), без выдуманных ставок и условий.
 */
@Service
public class AgentService {

    private static final String SYSTEM = """
            Ты — ассистент банка в чате с клиентом. Отвечай по-русски, коротко и по делу, 2–4 предложения.
            Не называй конкретные ставки, суммы и условия продуктов, если их нет в предоставленных материалах.
            Если вопрос требует действий с аккаунтом клиента, объясни, где это сделать в приложении.
            """;

    private static final String GROUNDING = """

            Клиент нажал на предложение банка «%s» и задал вопрос о нём. Отвечай о продукте строго по описанию ниже.
            Ставки, суммы и условия бери только отсюда; если в описании ответа нет — так и скажи и предложи
            посмотреть условия на странице продукта.
            Описание продукта: %s
            %s
            """;

    private final LlmGateway llm;

    public AgentService(LlmGateway llm) {
        this.llm = llm;
    }

    public String answer(List<DialogMessage> history, Campaign groundedOn, String disclaimer) {
        String system = groundedOn == null ? SYSTEM
                : SYSTEM + GROUNDING.formatted(groundedOn.name(), groundedOn.businessDescription(),
                        disclaimer == null ? "" : "Условия: " + disclaimer);
        String transcript = new TurnRequest("agent", null, 0, history).transcript(12);
        return llm.text(LlmStage.AGENT, system, transcript + "\n\nОтветь на последнюю реплику клиента.").value();
    }
}
