package artb.ads.campaign.model;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Закрытая таксономия тем, общая для диалогов и кампаний. */
public final class TopicTaxonomy {

    private final Map<String, Topic> byId;

    public TopicTaxonomy(Collection<Topic> topics) {
        Map<String, Topic> map = new LinkedHashMap<>();
        topics.forEach(t -> map.put(t.id(), t));
        this.byId = Map.copyOf(map);
    }

    public boolean contains(String id) {
        return byId.containsKey(id);
    }

    public boolean isSensitive(String id) {
        Topic t = byId.get(id);
        return t != null && t.sensitive();
    }

    public List<Topic> all() {
        return List.copyOf(byId.values());
    }

    public List<Topic> productTopics() {
        return byId.values().stream().filter(t -> !t.sensitive()).toList();
    }

    public Set<String> sensitiveIds() {
        return byId.values().stream().filter(Topic::sensitive).map(Topic::id).collect(Collectors.toUnmodifiableSet());
    }

    /** Список тем для промпта: «id — название: описание», стоп-темы помечены. */
    public String promptListing(boolean includeSensitive) {
        return byId.values().stream()
                .filter(t -> includeSensitive || !t.sensitive())
                .map(t -> "- " + t.id() + " — " + t.name() + ": " + t.description() + (t.sensitive() ? " [стоп-тема]" : ""))
                .collect(Collectors.joining("\n"));
    }
}
