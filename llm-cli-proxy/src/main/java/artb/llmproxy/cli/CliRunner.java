package artb.llmproxy.cli;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Запуск CLI: отдельный процесс в пустой временной директории, ввод в stdin, таймаут попытки,
 * ограничение параллельных процессов на бэкенд. CLI изредка зависает без ответа (замечено около
 * 2 вызовов из 100), поэтому при таймауте делается один повтор; другие ошибки не повторяются.
 */
public class CliRunner {

    private static final Logger log = LoggerFactory.getLogger(CliRunner.class);

    /** Переменные окружения родительской сессии Claude Code, из-за которых CLI считает запуск вложенным. */
    private static final List<String> STRIPPED_ENV_PREFIXES = List.of("CLAUDECODE", "CLAUDE_CODE_ENTRYPOINT");

    private static final ExecutorService IO = Executors.newVirtualThreadPerTaskExecutor();

    private final Duration timeout;
    private final int maxConcurrency;
    private final Map<String, Semaphore> permits = new ConcurrentHashMap<>();

    public CliRunner(Duration timeout, int maxConcurrency) {
        this.timeout = timeout;
        this.maxConcurrency = Math.max(1, maxConcurrency);
    }

    public CliResult run(CliBackend backend, CliCall call) {
        try {
            return runOnce(backend, call);
        } catch (CliException.Timeout e) {
            log.warn("{}: {}, повтор", backend.name(), e.getMessage());
            return runOnce(backend, call);
        }
    }

    private CliResult runOnce(CliBackend backend, CliCall call) {
        Semaphore semaphore = permits.computeIfAbsent(backend.name(), k -> new Semaphore(maxConcurrency));
        semaphore.acquireUninterruptibly();
        Path workDir = null;
        try {
            workDir = Files.createTempDirectory("llm-cli-");
            return backend.parse(exec(backend.command(call), backend.stdin(call), workDir));
        } catch (IOException e) {
            throw new CliException("Не удалось запустить " + backend.name(), e);
        } finally {
            semaphore.release();
            deleteQuietly(workDir);
        }
    }

    private String exec(List<String> command, String input, Path workDir) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(command).directory(workDir.toFile());
        pb.environment().keySet().removeIf(k -> STRIPPED_ENV_PREFIXES.stream().anyMatch(k::startsWith));
        Process process = pb.start();
        CompletableFuture<String> out = CompletableFuture.supplyAsync(() -> read(process.getInputStream()), IO);
        CompletableFuture<String> err = CompletableFuture.supplyAsync(() -> read(process.getErrorStream()), IO);
        try (Writer w = process.outputWriter(StandardCharsets.UTF_8)) {
            w.write(input == null ? "" : input);
        }
        try {
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new CliException.Timeout("CLI не ответил за " + timeout.toSeconds() + " с");
            }
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new CliException("Вызов CLI прерван", e);
        }
        String stdout = out.join();
        if (process.exitValue() != 0 && stdout.isBlank()) {
            throw new CliException("CLI завершился с кодом " + process.exitValue() + ": " + truncate(err.join()));
        }
        return stdout;
    }

    private static String read(InputStream in) {
        try (in) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String truncate(String s) {
        return s.length() > 500 ? s.substring(0, 500) + "…" : s;
    }

    private static void deleteQuietly(Path dir) {
        if (dir == null) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException ignored) {
            // временная директория, не критично
        }
    }
}
