package artb.ads.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import artb.ads.delivery.OfferTokenService.OfferToken;

class OfferTokenServiceTest {

    private final OfferTokenService tokens = new OfferTokenService("secret");

    @Test
    void roundTrip() {
        OfferToken t = new OfferToken(UUID.randomUUID(), "session-1", Instant.parse("2026-11-09T12:00:30Z"));
        assertThat(tokens.verify(tokens.sign(t))).contains(t);
    }

    @Test
    void tamperedOrForeignTokensAreRejected() {
        String token = tokens.sign(new OfferToken(UUID.randomUUID(), "s", Instant.now()));
        String forged = token.substring(0, token.indexOf('.')) + ".AAAA";
        assertThat(tokens.verify(forged)).isEmpty();
        assertThat(new OfferTokenService("other").verify(token)).isEmpty();
        assertThat(tokens.verify("garbage")).isEmpty();
        assertThat(tokens.verify(null)).isEmpty();
    }
}
