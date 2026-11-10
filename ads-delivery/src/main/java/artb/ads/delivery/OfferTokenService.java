package artb.ads.delivery;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Подписанный токен оффера: offerId, sessionId и срок, HMAC-SHA256. Поддельное событие не пройдёт. */
public class OfferTokenService {

    public record OfferToken(UUID offerId, String sessionId, Instant expiresAt) {}

    private static final Base64.Encoder ENC = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DEC = Base64.getUrlDecoder();

    private final byte[] secret;

    public OfferTokenService(String secret) {
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
    }

    public String sign(OfferToken t) {
        String payload = t.offerId() + "|" + t.sessionId() + "|" + t.expiresAt().getEpochSecond();
        String p = ENC.encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        return p + "." + ENC.encodeToString(hmac(p));
    }

    /** Пусто, если подпись не сходится или токен испорчен. Срок проверяет вызывающий: он зависит от события. */
    public Optional<OfferToken> verify(String token) {
        if (token == null) {
            return Optional.empty();
        }
        int dot = token.indexOf('.');
        if (dot <= 0) {
            return Optional.empty();
        }
        String p = token.substring(0, dot);
        try {
            byte[] sig = DEC.decode(token.substring(dot + 1));
            if (!MessageDigest.isEqual(sig, hmac(p))) {
                return Optional.empty();
            }
            String[] parts = new String(DEC.decode(p), StandardCharsets.UTF_8).split("\\|");
            return Optional.of(new OfferToken(UUID.fromString(parts[0]), parts[1], Instant.ofEpochSecond(Long.parseLong(parts[2]))));
        } catch (IllegalArgumentException | ArrayIndexOutOfBoundsException e) {
            return Optional.empty();
        }
    }

    private byte[] hmac(String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
