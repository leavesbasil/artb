package artb.ads.campaign.store;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

import artb.ads.campaign.model.AdFormat;
import artb.ads.campaign.model.Campaign;
import artb.ads.campaign.model.CampaignIntent;
import artb.ads.campaign.model.CampaignStatus;
import artb.ads.campaign.model.Creative;
import artb.ads.campaign.model.IntentPolarity;

public class JdbcCampaignRepository implements CampaignRepository {

    private static final String CAMPAIGN_COLUMNS = "id, external_key, name, status, business_description, topics, priority, "
            + "start_at, end_at, excluded_segments, excluded_topics, catalog_version, updated_at";

    private final JdbcClient jdbc;

    public JdbcCampaignRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void save(Campaign c) {
        jdbc.sql("""
                INSERT INTO campaign (id, external_key, name, status, business_description, topics, priority,
                                      start_at, end_at, excluded_segments, excluded_topics, catalog_version, updated_at)
                VALUES (?, ?, ?, ?, ?, ?::text[], ?, ?, ?, ?::text[], ?::text[], ?, ?)
                ON CONFLICT (id) DO UPDATE SET
                    external_key = EXCLUDED.external_key, name = EXCLUDED.name, status = EXCLUDED.status,
                    business_description = EXCLUDED.business_description, topics = EXCLUDED.topics,
                    priority = EXCLUDED.priority, start_at = EXCLUDED.start_at, end_at = EXCLUDED.end_at,
                    excluded_segments = EXCLUDED.excluded_segments, excluded_topics = EXCLUDED.excluded_topics,
                    catalog_version = EXCLUDED.catalog_version, updated_at = EXCLUDED.updated_at
                """)
                .param(c.id()).param(c.externalKey()).param(c.name()).param(c.status().name())
                .param(c.businessDescription()).param(PgArrays.textArray(c.topics())).param(c.priority())
                .param(odt(c.startAt())).param(odt(c.endAt()))
                .param(PgArrays.textArray(c.excludedSegments())).param(PgArrays.textArray(c.excludedTopics()))
                .param(c.catalogVersion()).param(odt(c.updatedAt()))
                .update();
    }

    @Override
    public Optional<Campaign> find(UUID id) {
        return jdbc.sql("SELECT " + CAMPAIGN_COLUMNS + " FROM campaign WHERE id = ?").param(id)
                .query(this::campaign).optional();
    }

    @Override
    public Optional<Campaign> findByExternalKey(String externalKey) {
        return jdbc.sql("SELECT " + CAMPAIGN_COLUMNS + " FROM campaign WHERE external_key = ?").param(externalKey)
                .query(this::campaign).optional();
    }

    @Override
    public List<Campaign> findLive(Instant now) {
        return jdbc.sql("SELECT " + CAMPAIGN_COLUMNS + " FROM campaign WHERE status = 'ACTIVE' "
                        + "AND (start_at IS NULL OR start_at <= ?) AND (end_at IS NULL OR end_at > ?)")
                .param(odt(now)).param(odt(now))
                .query(this::campaign).list();
    }

    @Override
    public List<Campaign> findAll(int limit) {
        return jdbc.sql("SELECT " + CAMPAIGN_COLUMNS + " FROM campaign ORDER BY updated_at DESC LIMIT ?").param(limit)
                .query(this::campaign).list();
    }

    @Override
    @Transactional
    public void saveCreatives(Collection<Creative> creatives) {
        for (Creative cr : creatives) {
            jdbc.sql("""
                    INSERT INTO campaign_creative (id, campaign_id, type, title, body, image_url, cta, url, question_text,
                                                   disclaimer, erid, approved_by, approved_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (id) DO UPDATE SET
                        type = EXCLUDED.type, title = EXCLUDED.title, body = EXCLUDED.body, image_url = EXCLUDED.image_url,
                        cta = EXCLUDED.cta, url = EXCLUDED.url, question_text = EXCLUDED.question_text,
                        disclaimer = EXCLUDED.disclaimer, erid = EXCLUDED.erid,
                        approved_by = EXCLUDED.approved_by, approved_at = EXCLUDED.approved_at
                    """)
                    .param(cr.id()).param(cr.campaignId()).param(cr.type().name()).param(cr.title()).param(cr.body())
                    .param(cr.imageUrl()).param(cr.cta()).param(cr.url()).param(cr.questionText())
                    .param(cr.disclaimer()).param(cr.erid()).param(cr.approvedBy()).param(odt(cr.approvedAt()))
                    .update();
        }
    }

    @Override
    public List<Creative> creatives(UUID campaignId) {
        return jdbc.sql("SELECT * FROM campaign_creative WHERE campaign_id = ? ORDER BY type, id").param(campaignId)
                .query(this::creative).list();
    }

    @Override
    public Map<UUID, List<Creative>> approvedCreatives(Collection<UUID> campaignIds) {
        if (campaignIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, List<Creative>> result = new LinkedHashMap<>();
        jdbc.sql("SELECT * FROM campaign_creative WHERE approved_at IS NOT NULL AND campaign_id = ANY(?::uuid[]) "
                        + "ORDER BY campaign_id, type, id")
                .param(PgArrays.uuidArray(campaignIds))
                .query(this::creative).list()
                .forEach(cr -> result.computeIfAbsent(cr.campaignId(), k -> new java.util.ArrayList<>()).add(cr));
        return result;
    }

    @Override
    @Transactional
    public void saveIntents(Collection<CampaignIntent> intents) {
        for (CampaignIntent i : intents) {
            jdbc.sql("""
                    INSERT INTO campaign_intent (id, campaign_id, text, polarity, approved) VALUES (?, ?, ?, ?, ?)
                    ON CONFLICT (id) DO UPDATE SET text = EXCLUDED.text, polarity = EXCLUDED.polarity,
                                                   approved = EXCLUDED.approved
                    """)
                    .param(i.id()).param(i.campaignId()).param(i.text()).param(i.polarity().name()).param(i.approved())
                    .update();
        }
    }

    @Override
    public List<CampaignIntent> intents(UUID campaignId) {
        return jdbc.sql("SELECT id, campaign_id, text, polarity, approved FROM campaign_intent WHERE campaign_id = ? "
                        + "ORDER BY polarity DESC, text")
                .param(campaignId)
                .query((rs, n) -> new CampaignIntent(rs.getObject("id", UUID.class), rs.getObject("campaign_id", UUID.class),
                        rs.getString("text"), IntentPolarity.valueOf(rs.getString("polarity")), rs.getBoolean("approved")))
                .list();
    }

    @Override
    public long nextCatalogVersion() {
        return jdbc.sql("SELECT nextval('catalog_version_seq')").query(Long.class).single();
    }

    @Override
    public long catalogVersion() {
        return jdbc.sql("SELECT coalesce(max(catalog_version), 0) FROM campaign").query(Long.class).single();
    }

    private Campaign campaign(ResultSet rs, int n) throws SQLException {
        return new Campaign(
                rs.getObject("id", UUID.class),
                rs.getString("external_key"),
                rs.getString("name"),
                CampaignStatus.valueOf(rs.getString("status")),
                rs.getString("business_description"),
                PgArrays.toList(rs.getArray("topics")),
                rs.getInt("priority"),
                instant(rs, "start_at"),
                instant(rs, "end_at"),
                PgArrays.toList(rs.getArray("excluded_segments")),
                PgArrays.toList(rs.getArray("excluded_topics")),
                rs.getLong("catalog_version"),
                instant(rs, "updated_at"));
    }

    private Creative creative(ResultSet rs, int n) throws SQLException {
        return new Creative(
                rs.getObject("id", UUID.class),
                rs.getObject("campaign_id", UUID.class),
                AdFormat.valueOf(rs.getString("type")),
                rs.getString("title"),
                rs.getString("body"),
                rs.getString("image_url"),
                rs.getString("cta"),
                rs.getString("url"),
                rs.getString("question_text"),
                rs.getString("disclaimer"),
                rs.getString("erid"),
                rs.getString("approved_by"),
                instant(rs, "approved_at"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime odt = rs.getObject(column, OffsetDateTime.class);
        return odt == null ? null : odt.toInstant();
    }

    private static OffsetDateTime odt(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }
}
