package artb.ads.matching;

import java.util.Optional;

import artb.ads.llm.LlmCall;
import artb.ads.llm.LlmGateway;
import artb.ads.llm.LlmStage;
import artb.ads.matching.llm.GateVerdict;

/** Этап 1: правила частоты, затем (опционально) быстрая модель проверяет стадию диалога. */
public class Gate {

    static final String SYSTEM = """
            Ты проверяешь, уместно ли сейчас показать клиенту банка рекламное предложение рядом с ответом ассистента.
            Неуместно (inappropriate=true), если клиент решает проблему: не проходит платёж, не работает приложение,
            заблокирована карта, непонятное списание; если клиент жалуется или расстроен; если последняя реплика
            ассистента — уточняющий вопрос, на который клиент ещё не ответил.
            Уместно (inappropriate=false), если разговор спокойный: клиент обсуждает планы, покупки, сбережения
            или спрашивает о продуктах и условиях банка. Вопрос клиента о продукте — уместно, даже если ответа
            ассистента на него ещё нет: реклама покажется рядом с ответом.
            reason — коротко, почему.
            """;

    private final FrequencyGuard frequency;
    private final LlmGateway llm;
    private final MatchingProperties props;

    public Gate(FrequencyGuard frequency, LlmGateway llm, MatchingProperties props) {
        this.frequency = frequency;
        this.llm = llm;
        this.props = props;
    }

    public Optional<NoAdReason> check(TurnRequest turn, Trace trace) {
        Optional<NoAdReason> rule = frequency.check(turn.sessionId(), turn.turnNo());
        if (rule.isPresent() || !props.isGateLlmCheck()) {
            return rule;
        }
        LlmCall<GateVerdict> call = llm.call(LlmStage.GATE, SYSTEM, turn.transcript(props.getHistoryTurns()), GateVerdict.class);
        trace.stage("gate", call);
        return call.value().inappropriate() ? Optional.of(NoAdReason.GATE_DIALOG_STAGE) : Optional.empty();
    }
}
