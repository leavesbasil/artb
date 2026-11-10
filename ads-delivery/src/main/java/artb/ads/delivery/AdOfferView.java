package artb.ads.delivery;

import java.time.Instant;

import artb.ads.campaign.model.AdFormat;

/** То, что получает поверхность. Внутренних id и скоров здесь нет. */
public record AdOfferView(
        String offerToken,
        Instant expiresAt,
        AdFormat format,
        String label,
        String erid,
        CreativeView creative,
        String why) {

    public record CreativeView(String title, String body, String imageUrl, String cta, String url, String disclaimer,
            String questionText) {}
}
