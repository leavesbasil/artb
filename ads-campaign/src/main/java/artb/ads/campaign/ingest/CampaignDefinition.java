package artb.ads.campaign.ingest;

import java.time.Instant;
import java.util.List;

/**
 * Готовая кампания с интентами и вопросами: импорт синтетического каталога и каталога для golden set
 * в обход LLM-обогащения и ручного одобрения.
 */
public record CampaignDefinition(
        String key,
        String name,
        String description,
        List<String> topics,
        Integer priority,
        Instant startAt,
        Instant endAt,
        List<String> excludedSegments,
        List<String> excludedTopics,
        CardSpec card,
        List<String> questions,
        List<String> positiveIntents,
        List<String> negativeIntents) {

    public CampaignDefinition {
        topics = topics == null ? List.of() : List.copyOf(topics);
        excludedSegments = excludedSegments == null ? List.of() : List.copyOf(excludedSegments);
        excludedTopics = excludedTopics == null ? List.of() : List.copyOf(excludedTopics);
        questions = questions == null ? List.of() : List.copyOf(questions);
        positiveIntents = positiveIntents == null ? List.of() : List.copyOf(positiveIntents);
        negativeIntents = negativeIntents == null ? List.of() : List.copyOf(negativeIntents);
    }

    public record CardSpec(String title, String body, String imageUrl, String cta, String url, String disclaimer,
            String erid) {}

    public CampaignDefinition withVariant(String newKey, String newName, int newPriority, List<String> newExcludedSegments,
            List<String> newPositiveIntents) {
        return new CampaignDefinition(newKey, newName, description, topics, newPriority, startAt, endAt,
                newExcludedSegments, excludedTopics, card, questions, newPositiveIntents, negativeIntents);
    }
}
