package artb.ads.delivery;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("ads.delivery")
public class DeliveryProperties {

    /** Сколько живёт выданный оффер: не показан за это время — EXPIRED и в статистику не идёт. */
    private Duration offerTtl = Duration.ofSeconds(30);
    /** Не больше одной рекламы за столько ходов. */
    private int minTurnsBetween = 3;
    private int maxPerSession = 3;
    /** Окно, в которое после показа принимаются клики и скрытие. */
    private Duration interactionWindow = Duration.ofHours(24);
    /** Секрет подписи offerToken (HMAC-SHA256). В проде — из хранилища секретов. */
    private String tokenSecret = "change-me-in-prod";
    private Duration defaultDeadline = Duration.ofMillis(2500);

    public Duration getOfferTtl() { return offerTtl; }
    public void setOfferTtl(Duration v) { this.offerTtl = v; }
    public int getMinTurnsBetween() { return minTurnsBetween; }
    public void setMinTurnsBetween(int v) { this.minTurnsBetween = v; }
    public int getMaxPerSession() { return maxPerSession; }
    public void setMaxPerSession(int v) { this.maxPerSession = v; }
    public Duration getInteractionWindow() { return interactionWindow; }
    public void setInteractionWindow(Duration v) { this.interactionWindow = v; }
    public String getTokenSecret() { return tokenSecret; }
    public void setTokenSecret(String v) { this.tokenSecret = v; }
    public Duration getDefaultDeadline() { return defaultDeadline; }
    public void setDefaultDeadline(Duration v) { this.defaultDeadline = v; }
}
