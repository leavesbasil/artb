package artb.ads.llm;

/** Этапы, которым в конфиге назначается своя модель. */
public enum LlmStage {
    GATE,
    INTENT,
    JUDGE,
    INGESTION,
    /** Модель самого агента в демо. Ничего не знает о рекламе. */
    AGENT;

    public String key() {
        return name().toLowerCase();
    }
}
