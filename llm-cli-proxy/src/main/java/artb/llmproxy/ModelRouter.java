package artb.llmproxy;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import artb.llmproxy.cli.CliBackend;

/**
 * Имя модели из запроса → CLI и модель для него: {@code claude/haiku} → claude + haiku,
 * {@code qwen} → qwen + модель по умолчанию из настроек qwen. Псевдонимы раскрываются сначала.
 */
public class ModelRouter {

    public record Route(CliBackend backend, String cliModel) {}

    private final Map<String, CliBackend> backends;
    private final Map<String, String> aliases;

    public ModelRouter(List<CliBackend> backends, Map<String, String> aliases) {
        this.backends = backends.stream().collect(Collectors.toUnmodifiableMap(CliBackend::name, Function.identity()));
        this.aliases = Map.copyOf(aliases);
    }

    public Route resolve(String requested) {
        if (requested == null || requested.isBlank()) {
            throw new IllegalArgumentException("не указана модель");
        }
        String name = aliases.getOrDefault(requested, requested);
        int slash = name.indexOf('/');
        String backendName = slash < 0 ? name : name.substring(0, slash);
        String model = slash < 0 ? null : name.substring(slash + 1);
        CliBackend backend = backends.get(backendName);
        if (backend == null) {
            throw new IllegalArgumentException("неизвестная модель «" + requested + "»: ожидается claude/<модель>, qwen[/<модель>] "
                    + "или псевдоним из proxy.aliases " + aliases.keySet());
        }
        return new Route(backend, model == null || model.isBlank() ? null : model);
    }

    /** Модели для {@code GET /v1/models}: бэкенды и псевдонимы. */
    public List<String> listed() {
        return java.util.stream.Stream.concat(
                backends.keySet().stream().sorted(),
                aliases.keySet().stream().sorted()).toList();
    }
}
