package artb.ads.delivery;

import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import artb.ads.matching.FrequencyGuard;
import artb.ads.matching.NoAdReason;

/**
 * Лимиты частоты считают показы (IMPRESSION от поверхности) и выданные, но ещё не истёкшие офферы:
 * следующий ход может прийти раньше события о показе.
 */
public class FrequencyPolicy implements FrequencyGuard {

    private final OfferRepository offers;
    private final DeliveryProperties props;
    private final Clock clock;

    public FrequencyPolicy(OfferRepository offers, DeliveryProperties props, Clock clock) {
        this.offers = offers;
        this.props = props;
        this.clock = clock;
    }

    @Override
    public Optional<NoAdReason> check(String sessionId, int turnNo) {
        List<OfferRecord> active = offers.activeOrShown(sessionId, clock.instant());
        if (active.size() >= props.getMaxPerSession()) {
            return Optional.of(NoAdReason.GATE_SESSION_LIMIT);
        }
        int lastTurn = active.stream().mapToInt(OfferRecord::turnNo).max().orElse(Integer.MIN_VALUE / 2);
        if (turnNo - lastTurn < props.getMinTurnsBetween()) {
            return Optional.of(NoAdReason.GATE_FREQUENCY);
        }
        return Optional.empty();
    }

    @Override
    public Set<UUID> usedCampaigns(String sessionId) {
        return offers.activeOrShown(sessionId, clock.instant()).stream()
                .map(OfferRecord::campaignId)
                .collect(Collectors.toUnmodifiableSet());
    }

    @Override
    public Set<String> suppressedTopics(String sessionId) {
        return offers.dismissedTopics(sessionId);
    }
}
