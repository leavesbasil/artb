package artb.ads.matching;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import artb.ads.llm.LlmCall;
import artb.ads.llm.LlmGateway;
import artb.ads.llm.LlmStage;

/** Фейковый шлюз: ответ этапа — функция от текста запроса. Считает вызовы по этапам. */
class ScriptedLlm implements LlmGateway {

    private final Map<LlmStage, Function<String, Object>> handlers = new EnumMap<>(LlmStage.class);
    private final Map<LlmStage, AtomicInteger> calls = new ConcurrentHashMap<>();

    ScriptedLlm on(LlmStage stage, Function<String, Object> handler) {
        handlers.put(stage, handler);
        return this;
    }

    int calls(LlmStage stage) {
        return calls.getOrDefault(stage, new AtomicInteger()).get();
    }

    @Override
    public <T> LlmCall<T> call(LlmStage stage, String system, String user, Class<T> type) {
        calls.computeIfAbsent(stage, s -> new AtomicInteger()).incrementAndGet();
        Function<String, Object> h = handlers.get(stage);
        if (h == null) {
            throw new AssertionError("Неожиданный вызов этапа " + stage);
        }
        return new LlmCall<>(type.cast(h.apply(user)), "fake-" + stage.key(), 1);
    }

    @Override
    public LlmCall<String> text(LlmStage stage, String system, String user) {
        throw new UnsupportedOperationException();
    }
}
