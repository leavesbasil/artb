package artb.ads.campaign.store;

import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;

import artb.ads.campaign.model.TaxonomyProvider;
import artb.ads.campaign.model.Topic;
import artb.ads.campaign.model.TopicTaxonomy;

public class TopicRepository implements TaxonomyProvider {

    private final JdbcClient jdbc;
    private volatile TopicTaxonomy cached;

    public TopicRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Таксономия меняется миграциями, поэтому кэшируется на время жизни приложения. */
    @Override
    public TopicTaxonomy taxonomy() {
        TopicTaxonomy t = cached;
        if (t == null) {
            List<Topic> topics = jdbc.sql("SELECT id, name, sensitive, description FROM topic ORDER BY sensitive, id")
                    .query((rs, n) -> new Topic(rs.getString("id"), rs.getString("name"), rs.getBoolean("sensitive"),
                            rs.getString("description")))
                    .list();
            t = new TopicTaxonomy(topics);
            cached = t;
        }
        return t;
    }
}
