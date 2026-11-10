package artb.ads.delivery;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;

import artb.ads.matching.MatchOutcome.CandidateTrace;
import artb.ads.matching.llm.IntentResult.TopicScore;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

public class OfferRepository {

    private final JdbcClient jdbc;
    private final JsonMapper json;

    public OfferRepository(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public void insert(OfferRecord o) {
        jdbc.sql("""
                INSERT INTO ad_offer (id, session_id, client_id, turn_no, created_at, expires_at, status, intent_queries,
                    commercial_intent, topics, candidates, campaign_id, creative_id, format, relevance, score, reason,
                    no_ad_reason, from_cache, latency_ms, models)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?::jsonb, ?::jsonb, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb)
                """)
                .param(o.id()).param(o.sessionId()).param(o.clientId()).param(o.turnNo())
                .param(odt(o.createdAt())).param(odt(o.expiresAt())).param(o.status().name())
                .param(toJson(o.intentQueries())).param(o.commercialIntent()).param(toJson(o.topics()))
                .param(toJson(o.candidates())).param(o.campaignId()).param(o.creativeId()).param(o.format())
                .param(o.relevance()).param(o.score()).param(o.reason()).param(o.noAdReason()).param(o.fromCache())
                .param(toJson(o.latencyMs())).param(toJson(o.models()))
                .update();
    }

    public Optional<OfferRecord> find(UUID id) {
        return jdbc.sql("SELECT * FROM ad_offer WHERE id = ?").param(id).query(this::map).optional();
    }

    public List<OfferRecord> bySession(String sessionId) {
        return jdbc.sql("SELECT * FROM ad_offer WHERE session_id = ? ORDER BY turn_no, created_at").param(sessionId)
                .query(this::map).list();
    }

    public List<OfferRecord> recent(int limit) {
        return jdbc.sql("SELECT * FROM ad_offer ORDER BY created_at DESC LIMIT ?").param(limit).query(this::map).list();
    }

    /** Показанные + выданные и ещё не истёкшие: мягкая бронь, пока не пришёл IMPRESSION. */
    public List<OfferRecord> activeOrShown(String sessionId, Instant now) {
        return jdbc.sql("SELECT * FROM ad_offer WHERE session_id = ? AND (status = 'SHOWN' "
                        + "OR (status = 'ISSUED' AND expires_at > ?)) ORDER BY turn_no")
                .param(sessionId).param(odt(now))
                .query(this::map).list();
    }

    public void updateStatus(UUID id, OfferStatus from, OfferStatus to) {
        jdbc.sql("UPDATE ad_offer SET status = ? WHERE id = ? AND status = ?").param(to.name()).param(id).param(from.name())
                .update();
    }

    public int expire(Instant now) {
        return jdbc.sql("UPDATE ad_offer SET status = 'EXPIRED' WHERE status = 'ISSUED' AND expires_at <= ?")
                .param(odt(now)).update();
    }

    /** true — событие записано впервые; false — повтор (идемпотентность по offer_id + type). */
    public boolean insertEvent(UUID offerId, EventType type, Instant occurredAt, Instant receivedAt, String surface) {
        return jdbc.sql("INSERT INTO ad_event (offer_id, type, occurred_at, received_at, surface) VALUES (?, ?, ?, ?, ?) "
                        + "ON CONFLICT (offer_id, type) DO NOTHING")
                .param(offerId).param(type.name()).param(odt(occurredAt)).param(odt(receivedAt)).param(surface)
                .update() == 1;
    }

    /** Темы кампаний, которые клиент скрыл в этой сессии. */
    public Set<String> dismissedTopics(String sessionId) {
        List<String> topics = jdbc.sql("""
                SELECT DISTINCT unnest(c.topics) FROM ad_event e
                JOIN ad_offer o ON o.id = e.offer_id
                JOIN campaign c ON c.id = o.campaign_id
                WHERE o.session_id = ? AND e.type = 'DISMISS'
                """).param(sessionId).query(String.class).list();
        return new HashSet<>(topics);
    }

    public List<String> eventTypes(UUID offerId) {
        return jdbc.sql("SELECT type FROM ad_event WHERE offer_id = ? ORDER BY received_at").param(offerId)
                .query(String.class).list();
    }

    private OfferRecord map(ResultSet rs, int n) throws SQLException {
        return new OfferRecord(
                rs.getObject("id", UUID.class),
                rs.getString("session_id"),
                rs.getString("client_id"),
                rs.getInt("turn_no"),
                instant(rs, "created_at"),
                instant(rs, "expires_at"),
                OfferStatus.valueOf(rs.getString("status")),
                fromJson(rs.getString("intent_queries"), new TypeReference<List<String>>() {}),
                (Double) rs.getObject("commercial_intent", Double.class),
                fromJson(rs.getString("topics"), new TypeReference<List<TopicScore>>() {}),
                fromJson(rs.getString("candidates"), new TypeReference<List<CandidateTrace>>() {}),
                rs.getObject("campaign_id", UUID.class),
                rs.getObject("creative_id", UUID.class),
                rs.getString("format"),
                rs.getObject("relevance", Double.class),
                rs.getObject("score", Double.class),
                rs.getString("reason"),
                rs.getString("no_ad_reason"),
                rs.getBoolean("from_cache"),
                fromJson(rs.getString("latency_ms"), new TypeReference<java.util.Map<String, Long>>() {}),
                fromJson(rs.getString("models"), new TypeReference<java.util.Map<String, String>>() {}));
    }

    private String toJson(Object value) {
        return value == null ? null : json.writeValueAsString(value);
    }

    private <T> T fromJson(String s, TypeReference<T> type) {
        return s == null ? null : json.readValue(s, type);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime odt = rs.getObject(column, OffsetDateTime.class);
        return odt == null ? null : odt.toInstant();
    }

    private static OffsetDateTime odt(Instant i) {
        return i == null ? null : i.atOffset(ZoneOffset.UTC);
    }
}
