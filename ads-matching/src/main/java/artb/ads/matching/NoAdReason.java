package artb.ads.matching;

/** Почему на ходе нет рекламы. Пишется в ad_offer.no_ad_reason. */
public enum NoAdReason {
    GATE_FREQUENCY,
    GATE_SESSION_LIMIT,
    GATE_DIALOG_STAGE,
    NO_ELIGIBLE,
    SENSITIVE_TOPIC,
    LOW_INTENT,
    NO_CANDIDATES,
    LOW_RELEVANCE,
    TIMEOUT,
    ERROR
}
