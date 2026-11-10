package artb.ads.delivery;

public enum EventType {
    IMPRESSION,
    CLICK,
    QUESTION_CLICK,
    DISMISS,
    /** Действие в сценарии; в прототипе не принимается — приходит фоновыми потоками. */
    CONVERSION;

    public boolean impliesImpression() {
        return this == CLICK || this == QUESTION_CLICK || this == DISMISS;
    }
}
