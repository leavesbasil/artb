package artb.ads.eval;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;

import tools.jackson.core.type.TypeReference;
import tools.jackson.dataformat.yaml.YAMLMapper;

public final class GoldenSet {

    private GoldenSet() {
    }

    public static List<GoldenCase> load() {
        try (InputStream in = GoldenSet.class.getResourceAsStream("/golden/golden-set.yaml")) {
            return YAMLMapper.builder().build().readValue(in, new TypeReference<List<GoldenCase>>() {});
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
