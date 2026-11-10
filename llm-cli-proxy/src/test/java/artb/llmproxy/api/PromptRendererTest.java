package artb.llmproxy.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

class PromptRendererTest {

    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void singleUserMessageGoesAsIs() {
        var r = PromptRenderer.render(json.readTree("""
                [{"role":"system","content":"сис"},{"role":"user","content":[{"type":"text","text":"привет"}]}]"""));
        assertThat(r.system()).isEqualTo("сис");
        assertThat(r.user()).isEqualTo("привет");
    }

    @Test
    void conversationBecomesTranscript() {
        var r = PromptRenderer.render(json.readTree("""
                [{"role":"user","content":"раз"},{"role":"assistant","content":"два"},{"role":"user","content":"три"}]"""));
        assertThat(r.user()).contains("Пользователь: раз").contains("Ассистент: два").contains("Пользователь: три");
    }

    @Test
    void toolMessagesAreRejected() {
        assertThatThrownBy(() -> PromptRenderer.render(json.readTree("[{\"role\":\"tool\",\"content\":\"x\"}]")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
