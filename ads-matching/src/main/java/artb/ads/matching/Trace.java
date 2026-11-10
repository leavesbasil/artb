package artb.ads.matching;

import java.util.LinkedHashMap;
import java.util.Map;

import artb.ads.llm.LlmCall;

/** Задержки и модели по этапам одного подбора. */
public class Trace {

    private final Map<String, Long> latencyMs = new LinkedHashMap<>();
    private final Map<String, String> models = new LinkedHashMap<>();

    public void stage(String name, LlmCall<?> call) {
        latencyMs.put(name, call.latencyMs());
        models.put(name, call.model());
    }

    public void stage(String name, long ms) {
        latencyMs.put(name, ms);
    }

    public Map<String, Long> latencyMs() {
        return Map.copyOf(latencyMs);
    }

    public Map<String, String> models() {
        return Map.copyOf(models);
    }
}
