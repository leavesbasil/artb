package artb.ads.matching;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import artb.ads.campaign.model.AdFormat;
import artb.ads.campaign.model.Campaign;
import artb.ads.campaign.model.Creative;
import artb.ads.llm.LlmCall;
import artb.ads.llm.LlmGateway;
import artb.ads.llm.LlmStage;
import artb.ads.matching.CandidateRetriever.Retrieved;
import artb.ads.matching.llm.JudgeResult;
import artb.ads.matching.llm.JudgeResult.Judgement;
import artb.ads.ranking.JudgedCandidate;

/**
 * Этап 4: сильная модель одним вызовом оценивает всех кандидатов — релевантность, формат,
 * конкретный креатив и фразу «почему вы это видите». В промпте короткие id (c1, c1-q2), а не UUID:
 * модель их не исказит.
 */
public class Judge {

    static final String SYSTEM = """
            Ты решаешь, какое предложение банка уместно показать клиенту рядом с ответом ассистента банка.
            Оцени каждого кандидата из списка:

            relevance — от 0 до 1: насколько продукт отвечает текущей ситуации клиента.
              0.8 и выше — прямо отвечает потребности; около 0.5 — связан косвенно;
              ниже 0.3 — не связан или неуместен (клиент жалуется, у него трудности,
              клиент уже сказал, что продукт у него есть или не нужен).
            format — QUESTION, если потребность скрытая или клиент только начинает думать;
              CARD, если клиент прямо выбирает продукт.
            creativeId — id креатива выбранного формата из списка этого кандидата.
            reason — до 10 слов, обращённых к клиенту, почему он видит предложение, без цифр и обещаний,
              например «Вы обсуждаете бюджет ремонта». Для кандидатов с relevance ниже 0.3 — два-три слова,
              например «Не по теме».

            Верни оценку для каждого кандидата.
            """;

    record Labeled(String label, UUID campaignId, Map<String, Creative> creatives) {}

    private final LlmGateway llm;
    private final MatchingProperties props;

    public Judge(LlmGateway llm, MatchingProperties props) {
        this.llm = llm;
        this.props = props;
    }

    public List<JudgedCandidate> judge(TurnRequest turn, List<Retrieved> retrieved, Map<UUID, Campaign> campaigns,
            Map<UUID, List<Creative>> creatives, Trace trace) {
        Map<String, Labeled> byLabel = new LinkedHashMap<>();
        Map<UUID, Double> similarity = new HashMap<>();
        StringBuilder user = new StringBuilder("Диалог:\n").append(turn.transcript(props.getHistoryTurns()))
                .append("\n\nКандидаты:\n");
        int n = 0;
        for (Retrieved r : retrieved) {
            Campaign c = campaigns.get(r.campaignId());
            List<Creative> list = creatives.getOrDefault(r.campaignId(), List.of());
            if (c == null || list.isEmpty()) {
                continue;
            }
            String label = "c" + (++n);
            Map<String, Creative> creativeLabels = new LinkedHashMap<>();
            int cards = 0;
            int questions = 0;
            for (Creative cr : list) {
                String id = cr.type() == AdFormat.CARD ? label + "-card" + (++cards) : label + "-q" + (++questions);
                creativeLabels.put(id, cr);
            }
            byLabel.put(label, new Labeled(label, c.id(), creativeLabels));
            similarity.put(c.id(), r.similarity());
            user.append('\n').append(label).append(": ").append(c.name()).append('\n')
                    .append("Описание: ").append(c.businessDescription()).append('\n')
                    .append("Креативы:\n");
            creativeLabels.forEach((id, cr) -> user.append("- ").append(id).append(" [").append(cr.type()).append("]: ")
                    .append(cr.type() == AdFormat.CARD ? cr.title() + " — " + cr.body() : cr.questionText()).append('\n'));
        }
        if (byLabel.isEmpty()) {
            return List.of();
        }
        LlmCall<JudgeResult> call = llm.call(LlmStage.JUDGE, SYSTEM, user.toString(), JudgeResult.class);
        trace.stage("judge", call);

        List<JudgedCandidate> result = new ArrayList<>();
        for (Judgement j : call.value().judgements()) {
            Labeled l = j.candidateId() == null ? null : byLabel.get(j.candidateId().trim());
            if (l == null) {
                continue;
            }
            AdFormat format = parseFormat(j.format());
            Creative creative = j.creativeId() == null ? null : l.creatives().get(j.creativeId().trim());
            if (creative == null || creative.type() != format) {
                creative = l.creatives().values().stream().filter(cr -> cr.type() == format).findFirst()
                        .orElse(l.creatives().values().iterator().next());
            }
            Campaign c = campaigns.get(l.campaignId());
            result.add(new JudgedCandidate(l.campaignId(), creative.id(), creative.type(),
                    Math.max(0, Math.min(1, j.relevance())), j.reason(), c.priority(), similarity.get(l.campaignId())));
        }
        return result;
    }

    private static AdFormat parseFormat(String s) {
        if (s == null) {
            return AdFormat.QUESTION;
        }
        return s.trim().toUpperCase(Locale.ROOT).startsWith("CARD") ? AdFormat.CARD : AdFormat.QUESTION;
    }
}
