package artb.llmproxy.cli;

public class CliException extends RuntimeException {

    public CliException(String message) {
        super(message);
    }

    public CliException(String message, Throwable cause) {
        super(message, cause);
    }

    /** CLI не ответил за таймаут попытки. */
    public static class Timeout extends CliException {
        public Timeout(String message) {
            super(message);
        }
    }
}
