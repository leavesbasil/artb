package artb.ads.campaign.store;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

import artb.ads.campaign.model.IntentPolarity;

/**
 * Векторы хранятся в {@code campaign_intent.embedding} (pgvector), поиск — косинусное расстояние.
 * Индекса HNSW нет: при тысячах кампаний и фильтре по eligible set полный перебор укладывается
 * в миллисекунды, а колонка без фиксированной размерности позволяет менять модель эмбеддингов.
 */
public class PgIntentIndex implements IntentIndex {

    private final JdbcClient jdbc;

    public PgIntentIndex(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public void index(UUID campaignId, String modelId, List<IndexedIntent> intents) {
        jdbc.sql("UPDATE campaign_intent SET embedding = NULL, embedding_model = NULL WHERE campaign_id = ?")
                .param(campaignId).update();
        for (IndexedIntent i : intents) {
            jdbc.sql("UPDATE campaign_intent SET embedding = ?::vector, embedding_model = ? WHERE id = ?")
                    .param(PgArrays.vector(i.vector())).param(modelId).param(i.intentId())
                    .update();
        }
    }

    @Override
    public List<IntentHit> search(float[] query, String modelId, Collection<UUID> campaignIds, int limit) {
        if (campaignIds.isEmpty()) {
            return List.of();
        }
        String vector = PgArrays.vector(query);
        return jdbc.sql("""
                SELECT campaign_id, id, text, polarity, 1 - (embedding <=> ?::vector) AS similarity
                FROM campaign_intent
                WHERE approved AND embedding IS NOT NULL AND embedding_model = ? AND campaign_id = ANY(?::uuid[])
                ORDER BY embedding <=> ?::vector
                LIMIT ?
                """)
                .param(vector).param(modelId).param(PgArrays.uuidArray(campaignIds)).param(vector).param(limit)
                .query((rs, n) -> new IntentHit(
                        rs.getObject("campaign_id", UUID.class),
                        rs.getObject("id", UUID.class),
                        rs.getString("text"),
                        IntentPolarity.valueOf(rs.getString("polarity")),
                        rs.getDouble("similarity")))
                .list();
    }
}
