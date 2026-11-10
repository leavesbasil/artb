package artb.ads.delivery;

public enum OfferStatus {
    ISSUED,
    SHOWN,
    EXPIRED,
    /** Решение «без рекламы»: пишется для анализа, поверхности не отдаётся. */
    NO_AD
}
