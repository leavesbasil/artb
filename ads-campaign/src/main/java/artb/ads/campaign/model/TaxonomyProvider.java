package artb.ads.campaign.model;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Источник таксономии тем. */
@FunctionalInterface
public interface TaxonomyProvider {

    TopicTaxonomy taxonomy();

    /**
     * Таксономия из миграции {@code V2__topics.sql} без базы: для тестов и прогона golden set.
     * Единственный источник правды — миграция.
     */
    static TopicTaxonomy fromMigration() {
        Pattern row = Pattern.compile("\\('([^']+)',\\s*'([^']+)',\\s*(true|false),\\s*'([^']+)'\\)");
        try (InputStream in = TaxonomyProvider.class.getResourceAsStream("/db/migration/V2__topics.sql")) {
            String sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            Matcher m = row.matcher(sql);
            List<Topic> topics = new ArrayList<>();
            while (m.find()) {
                topics.add(new Topic(m.group(1), m.group(2), Boolean.parseBoolean(m.group(3)), m.group(4)));
            }
            return new TopicTaxonomy(topics);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
