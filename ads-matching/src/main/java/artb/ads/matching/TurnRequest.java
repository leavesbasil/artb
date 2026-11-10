package artb.ads.matching;

import java.util.List;
import java.util.stream.Collectors;

/** Ход диалога: история до текущей реплики клиента включительно (ответа агента на неё ещё нет). */
public record TurnRequest(String sessionId, String clientId, int turnNo, List<DialogMessage> messages) {

    public TurnRequest {
        messages = List.copyOf(messages);
    }

    /** Последние {@code n} реплик в виде текста для промпта. */
    public String transcript(int n) {
        List<DialogMessage> tail = messages.subList(Math.max(0, messages.size() - n), messages.size());
        return tail.stream()
                .map(m -> (m.role() == DialogMessage.Role.CLIENT ? "Клиент: " : "Ассистент: ") + m.text())
                .collect(Collectors.joining("\n"));
    }
}
