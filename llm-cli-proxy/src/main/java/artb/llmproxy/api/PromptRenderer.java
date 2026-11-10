package artb.llmproxy.api;

import java.util.ArrayList;
import java.util.List;

import tools.jackson.databind.JsonNode;

/**
 * OpenAI-сообщения → системный промпт и текст запроса для CLI. System/developer склеиваются в
 * системный промпт. Одно сообщение пользователя уходит как есть; переписка из нескольких
 * сообщений — транскриптом с ролями, последнее — как текущий запрос.
 */
public final class PromptRenderer {

    public record Rendered(String system, String user) {}

    private PromptRenderer() {
    }

    public static Rendered render(JsonNode messages) {
        if (messages == null || !messages.isArray() || messages.isEmpty()) {
            throw new IllegalArgumentException("messages должен быть непустым массивом");
        }
        List<String> system = new ArrayList<>();
        List<String[]> dialog = new ArrayList<>();
        for (JsonNode m : messages) {
            String role = m.path("role").asString("");
            String text = text(m.get("content"));
            switch (role) {
                case "system", "developer" -> system.add(text);
                case "user", "assistant" -> dialog.add(new String[] {role, text});
                default -> throw new IllegalArgumentException("роль «" + role + "» не поддерживается (инструменты прокси не умеет)");
            }
        }
        if (dialog.isEmpty()) {
            throw new IllegalArgumentException("нет ни одного сообщения user");
        }
        String user;
        if (dialog.size() == 1) {
            user = dialog.getFirst()[1];
        } else {
            StringBuilder sb = new StringBuilder("Переписка:\n");
            for (String[] turn : dialog) {
                sb.append("user".equals(turn[0]) ? "Пользователь: " : "Ассистент: ").append(turn[1]).append("\n\n");
            }
            sb.append("Ответь на последнее сообщение пользователя.");
            user = sb.toString();
        }
        return new Rendered(String.join("\n\n", system), user);
    }

    private static String text(JsonNode content) {
        if (content == null || content.isNull()) {
            return "";
        }
        if (content.isString()) {
            return content.asString();
        }
        if (content.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode part : content) {
                if (!"text".equals(part.path("type").asString(""))) {
                    throw new IllegalArgumentException("поддерживаются только текстовые части сообщений");
                }
                if (!sb.isEmpty()) {
                    sb.append('\n');
                }
                sb.append(part.path("text").asString(""));
            }
            return sb.toString();
        }
        throw new IllegalArgumentException("неподдерживаемый формат content");
    }
}
