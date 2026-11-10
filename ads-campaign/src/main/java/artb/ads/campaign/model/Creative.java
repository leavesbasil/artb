package artb.ads.campaign.model;

import java.time.Instant;
import java.util.UUID;

public record Creative(
        UUID id,
        UUID campaignId,
        AdFormat type,
        String title,
        String body,
        String imageUrl,
        String cta,
        String url,
        String questionText,
        String disclaimer,
        String erid,
        String approvedBy,
        Instant approvedAt) {

    public boolean approved() {
        return approvedAt != null;
    }

    public Creative approve(String by, Instant at) {
        return new Creative(id, campaignId, type, title, body, imageUrl, cta, url, questionText, disclaimer, erid, by, at);
    }

    public static Creative card(UUID campaignId, String title, String body, String imageUrl, String cta, String url,
            String disclaimer, String erid) {
        return new Creative(UUID.randomUUID(), campaignId, AdFormat.CARD, title, body, imageUrl, cta, url, null,
                disclaimer, erid, null, null);
    }

    public static Creative question(UUID campaignId, String questionText) {
        return new Creative(UUID.randomUUID(), campaignId, AdFormat.QUESTION, null, null, null, null, null,
                questionText, null, null, null, null);
    }

    /** Текст креатива целиком: для проверки дисклеймера и для промпта судьи. */
    public String fullText() {
        return type == AdFormat.CARD
                ? String.join(" ", nz(title), nz(body), nz(cta))
                : nz(questionText);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
