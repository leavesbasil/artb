package artb.ads.campaign.synthetic;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import artb.ads.campaign.ingest.CampaignDefinition;
import tools.jackson.core.type.TypeReference;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * Синтетический каталог: базовые продукты из {@code synthetic/products.yaml} и их варианты
 * (другой приоритет, чёрный список сегментов и подмножество интентов) до нужного числа кампаний.
 * Базовые продукты сохраняют свои ключи — на них ссылается golden set.
 */
public final class SyntheticCatalog {

    public static final List<String> SEGMENTS = List.of(
            "minor", "bankruptcy_risk", "overdue_debt", "has_credit_card", "has_mortgage", "has_car_loan",
            "salary_client", "premium", "pensioner", "no_marketing_consent", "new_client", "business_owner",
            "has_deposit", "has_investments");

    private SyntheticCatalog() {
    }

    public static List<CampaignDefinition> baseProducts() {
        try (InputStream in = SyntheticCatalog.class.getResourceAsStream("/synthetic/products.yaml")) {
            return YAMLMapper.builder().findAndAddModules().build()
                    .readValue(in, new TypeReference<List<CampaignDefinition>>() {});
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static List<CampaignDefinition> generate(int count, long seed) {
        List<CampaignDefinition> base = baseProducts();
        List<CampaignDefinition> result = new ArrayList<>(base.subList(0, Math.min(count, base.size())));
        Random random = new Random(seed);
        for (int i = base.size(); i < count; i++) {
            CampaignDefinition b = base.get(i % base.size());
            int variant = i / base.size();
            Set<String> excluded = new LinkedHashSet<>(b.excludedSegments());
            if (random.nextDouble() < 0.6) {
                excluded.add(SEGMENTS.get(random.nextInt(SEGMENTS.size())));
            }
            List<String> intents = new ArrayList<>(b.positiveIntents());
            Collections.shuffle(intents, random);
            int keep = Math.max(4, (int) Math.round(intents.size() * (0.6 + 0.4 * random.nextDouble())));
            result.add(b.withVariant(b.key() + "-v" + variant, b.name() + " (вариант " + variant + ")",
                    10 + random.nextInt(80), List.copyOf(excluded), intents.subList(0, keep)));
        }
        return result;
    }
}
