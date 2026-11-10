package artb.llmproxy.cli;

/**
 * Один вызов CLI.
 *
 * @param system     системный промпт (может быть пустым)
 * @param user       текст запроса; для многоходового диалога — транскрипт
 * @param model      модель для CLI или null — модель по умолчанию из настроек самого CLI
 * @param jsonSchema JSON-схема ответа или null
 */
public record CliCall(String system, String user, String model, String jsonSchema) {

    public boolean hasSchema() {
        return jsonSchema != null && !jsonSchema.isBlank();
    }
}
