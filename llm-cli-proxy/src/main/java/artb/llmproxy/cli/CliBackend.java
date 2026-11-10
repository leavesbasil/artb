package artb.llmproxy.cli;

import java.util.List;

/** Как вызвать конкретный CLI и как разобрать его вывод. */
public interface CliBackend {

    /** Имя бэкенда в имени модели: {@code claude/haiku}, {@code qwen}. */
    String name();

    List<String> command(CliCall call);

    String stdin(CliCall call);

    CliResult parse(String stdout);

    /** Гарантирует ли CLI ответ по JSON-схеме сам (иначе схему добавляет прокси). */
    boolean nativeSchema();
}
