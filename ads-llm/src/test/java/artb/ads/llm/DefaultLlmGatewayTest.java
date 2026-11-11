package artb.ads.llm;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import artb.ads.llm.masking.RegexPiiMasker;

class DefaultLlmGatewayTest {

    record Intent(double commercialIntent, List<String> queries) {}

    @Test
    void acceptsJsonWrappedInBackticksOrText() {
        assertThat(DefaultLlmGateway.extractJson("```{\"a\":1}`")).isEqualTo("{\"a\":1}");
        assertThat(DefaultLlmGateway.extractJson("```json\n{\"a\":{\"b\":[1]}}\n```")).isEqualTo("{\"a\":{\"b\":[1]}}");
        assertThat(DefaultLlmGateway.extractJson("Ответ: [1,2] готово")).isEqualTo("[1,2]");
        assertThat(DefaultLlmGateway.extractJson("не json")).isEqualTo("не json");

        ChatModel model = prompt -> new ChatResponse(List.of(new Generation(
                new AssistantMessage("```{\"commercialIntent\":0.6,\"queries\":[\"вклад\"]}`"))));
        var gateway = new DefaultLlmGateway(fixed(model), new RegexPiiMasker(), true);

        assertThat(gateway.call(LlmStage.INTENT, "s", "u", Intent.class).value().commercialIntent()).isEqualTo(0.6);
    }

    @Test
    void retriesOnceWhenAnswerBreaksSchemaAndMasksInput() {
        Queue<String> answers = new ConcurrentLinkedQueue<>(List.of("не json", "{\"commercialIntent\":0.8,\"queries\":[\"кредит\"]}"));
        List<Prompt> seen = new ArrayList<>();
        ChatModel model = prompt -> {
            seen.add(prompt);
            return new ChatResponse(List.of(new Generation(new AssistantMessage(answers.poll()))));
        };
        var gateway = new DefaultLlmGateway(fixed(model), new RegexPiiMasker(), true);

        LlmCall<Intent> call = gateway.call(LlmStage.INTENT, "system", "звоните +7 916 123-45-67", Intent.class);

        assertThat(call.value().commercialIntent()).isEqualTo(0.8);
        assertThat(seen).hasSize(2);
        assertThat(seen.getFirst().getUserMessage().getText()).contains("[ТЕЛЕФОН]").doesNotContain("916");
    }

    private static StageModels fixed(ChatModel model) {
        return new StageModels(new LlmProperties(), null) {
            @Override
            public ChatModel forStage(LlmStage stage) {
                return model;
            }

            @Override
            public String idFor(LlmStage stage) {
                return "fake";
            }
        };
    }
}
