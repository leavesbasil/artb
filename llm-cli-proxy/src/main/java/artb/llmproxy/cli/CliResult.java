package artb.llmproxy.cli;

/** Разобранный вывод CLI. Токены и стоимость — если CLI их сообщает. */
public record CliResult(String text, String error, Double costUsd, long inputTokens, long outputTokens) {

    public static CliResult ok(String text) {
        return new CliResult(text, null, null, 0, 0);
    }

    public static CliResult ok(String text, Double costUsd, long inputTokens, long outputTokens) {
        return new CliResult(text, null, costUsd, inputTokens, outputTokens);
    }

    public static CliResult failed(String error) {
        return new CliResult(null, error, null, 0, 0);
    }

    public boolean isError() {
        return error != null;
    }
}
