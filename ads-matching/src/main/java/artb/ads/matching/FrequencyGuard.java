package artb.ads.matching;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Правила частоты. Реализует модуль доставки: он знает о показах (IMPRESSION от поверхности)
 * и выданных, но ещё не истёкших офферах.
 */
public interface FrequencyGuard {

    Optional<NoAdReason> check(String sessionId, int turnNo);

    /** Кампании, уже показанные в сессии или забронированные выданным оффером. */
    Set<UUID> usedCampaigns(String sessionId);

    /** Темы, подавленные после DISMISS. */
    Set<String> suppressedTopics(String sessionId);

    FrequencyGuard NONE = new FrequencyGuard() {
        @Override
        public Optional<NoAdReason> check(String sessionId, int turnNo) {
            return Optional.empty();
        }

        @Override
        public Set<UUID> usedCampaigns(String sessionId) {
            return Set.of();
        }

        @Override
        public Set<String> suppressedTopics(String sessionId) {
            return Set.of();
        }
    };
}
