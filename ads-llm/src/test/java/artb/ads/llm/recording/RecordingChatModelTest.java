package artb.ads.llm.recording;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import artb.ads.llm.LlmException;

class RecordingChatModelTest {

    @TempDir
    Path dir;

    @Test
    void recordsOnceAndReplaysWithoutDelegate() {
        AtomicInteger calls = new AtomicInteger();
        ChatModel delegate = prompt -> {
            calls.incrementAndGet();
            return new ChatResponse(List.of(new Generation(new AssistantMessage("ответ"))));
        };
        new RecordingChatModel(delegate, "m", RecordingChatModel.Mode.RECORD, dir).call(new Prompt("вопрос"));
        var replay = new RecordingChatModel(prompt -> { throw new AssertionError("не должен вызываться"); },
                "m", RecordingChatModel.Mode.REPLAY, dir);

        assertThat(replay.call(new Prompt("вопрос")).getResult().getOutput().getText()).isEqualTo("ответ");
        assertThat(calls).hasValue(1);
    }

    @Test
    void replayWithoutCassetteFails() {
        var replay = new RecordingChatModel(prompt -> null, "m", RecordingChatModel.Mode.REPLAY, dir);
        assertThatThrownBy(() -> replay.call(new Prompt("новый вопрос"))).isInstanceOf(LlmException.class);
    }
}
