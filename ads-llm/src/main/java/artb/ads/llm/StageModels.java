package artb.ads.llm;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;

import artb.ads.llm.LlmProperties.StageModel;
import artb.ads.llm.recording.RecordingChatModel;

/**
 * Создаёт и кэширует {@link ChatModel} для каждого этапа по конфигу. Приложение знает только
 * OpenAI-совместимый протокол: в проде это сервер внутри контура, при отладке — локальный
 * llm-cli-proxy поверх Claude Code CLI или Qwen Code CLI.
 */
public class StageModels {

    private final LlmProperties props;
    private final Map<String, ChatModel> cache = new ConcurrentHashMap<>();

    public StageModels(LlmProperties props) {
        this.props = props;
    }

    public ChatModel forStage(LlmStage stage) {
        StageModel sm = props.modelFor(stage);
        return cache.computeIfAbsent(sm.id(), id -> wrap(create(sm), sm));
    }

    public String idFor(LlmStage stage) {
        return props.modelFor(stage).id();
    }

    private ChatModel create(StageModel sm) {
        if (!"internal".equals(sm.provider())) {
            throw new IllegalArgumentException("Неизвестный провайдер модели: " + sm.provider()
                    + ". Поддерживается internal — любой OpenAI-совместимый сервер (для CLI — llm-cli-proxy)");
        }
        LlmProperties.Internal in = props.getInternal();
        OpenAiChatModel model = OpenAiChatModel.builder()
                .options(OpenAiChatOptions.builder()
                        .baseUrl(in.getBaseUrl())
                        .apiKey(in.getApiKey())
                        .model(sm.model())
                        .timeout(in.getTimeout())
                        .maxRetries(in.getMaxRetries())
                        .build())
                .build();
        return new OpenAiCompatibleChatModel(model, sm.model(), in.isNativeSchema());
    }

    private ChatModel wrap(ChatModel model, StageModel sm) {
        RecordingChatModel.Mode mode = RecordingChatModel.Mode.parse(props.getRecording().getMode());
        if (mode == RecordingChatModel.Mode.OFF) {
            return model;
        }
        return new RecordingChatModel(model, sm.id(), mode, props.getRecording().getDir());
    }
}
