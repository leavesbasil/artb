package artb.ads.ranking;

import java.time.Instant;

public record RankingContext(String sessionId, int turnNo, Instant now) {
}
