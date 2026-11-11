package artb.ads.matching;

import java.util.LinkedHashMap;
import java.util.Map;

import artb.ads.llm.LlmCall;

/** Задержки и модели по этапам одного подбора. Пишется из потока подбора, читается при дедлайне — поэтому synchronized. */
public class Trace {

    private final Map<String, Long> latencyMs = new LinkedHashMap<>();
    private final Map<String, String> models = new LinkedHashMap<>();

    public synchronized void stage(String name, LlmCall<?> call) {
        latencyMs.put(name, call.latencyMs());
        models.put(name, call.model());
    }

    public synchronized void stage(String name, long ms) {
        latencyMs.put(name, ms);
    }

    public synchronized Map<String, Long> latencyMs() {
        return Map.copyOf(latencyMs);
    }

    public synchronized Map<String, String> models() {
        return Map.copyOf(models);
    }
}
