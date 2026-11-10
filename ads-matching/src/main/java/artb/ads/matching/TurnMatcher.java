package artb.ads.matching;

import java.time.Duration;

/** Подбор на ходе с дедлайном. Реализация — {@link AdMatcher}; доставка зависит только от интерфейса. */
@FunctionalInterface
public interface TurnMatcher {

    MatchOutcome match(TurnRequest turn, Duration deadline);
}
