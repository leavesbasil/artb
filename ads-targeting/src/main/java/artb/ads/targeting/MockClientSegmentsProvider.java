package artb.ads.targeting;

import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;

/**
 * Мок сегментов: явные профили из конфига ({@code ads.targeting.mock-clients}), а для остальных
 * clientId — детерминированный псевдослучайный набор, чтобы один клиент всегда получал одно и то же.
 */
public class MockClientSegmentsProvider implements ClientSegmentsProvider {

    private final Map<String, List<String>> explicitProfiles;
    private final List<String> knownSegments;
    private final double probability;

    public MockClientSegmentsProvider(Map<String, List<String>> explicitProfiles, List<String> knownSegments,
            double probability) {
        this.explicitProfiles = Map.copyOf(explicitProfiles);
        this.knownSegments = List.copyOf(knownSegments);
        this.probability = probability;
    }

    @Override
    public Set<String> segments(String clientId) {
        List<String> explicit = explicitProfiles.get(clientId);
        if (explicit != null) {
            return Set.copyOf(explicit);
        }
        Random random = new Random(clientId == null ? 0 : clientId.hashCode());
        Set<String> result = new TreeSet<>();
        for (String s : knownSegments) {
            if (random.nextDouble() < probability) {
                result.add(s);
            }
        }
        return result;
    }
}
