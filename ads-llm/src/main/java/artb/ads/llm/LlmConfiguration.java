package artb.ads.llm;

import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import artb.ads.llm.embedding.HashingTextEmbedder;
import artb.ads.llm.embedding.SpringAiTextEmbedder;
import artb.ads.llm.embedding.TextEmbedder;
import artb.ads.llm.gigachat.GigaChatClients;
import artb.ads.llm.masking.PiiMasker;
import artb.ads.llm.masking.RegexPiiMasker;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(LlmProperties.class)
public class LlmConfiguration {

    @Bean
    @ConditionalOnMissingBean
    PiiMasker piiMasker() {
        return new RegexPiiMasker();
    }

    @Bean
    GigaChatClients gigaChatClients(LlmProperties props) {
        return new GigaChatClients(props.getGigachat());
    }

    @Bean
    StageModels stageModels(LlmProperties props, GigaChatClients gigaChat) {
        return new StageModels(props, gigaChat);
    }

    @Bean
    @ConditionalOnMissingBean
    LlmGateway llmGateway(StageModels models, PiiMasker masker, LlmProperties props) {
        return new DefaultLlmGateway(models, masker, props.getMasking().isEnabled());
    }

    @Bean
    @ConditionalOnMissingBean
    TextEmbedder textEmbedder(LlmProperties props, GigaChatClients gigaChat) {
        LlmProperties.Embedding e = props.getEmbedding();
        return switch (e.getProvider()) {
            case "hashing" -> new HashingTextEmbedder(e.getDimensions());
            case "gigachat" -> new SpringAiTextEmbedder(gigaChat.embeddingModel(e.getModel()), "gigachat:" + e.getModel());
            case "internal" -> new SpringAiTextEmbedder(OpenAiEmbeddingModel.builder()
                    .options(OpenAiEmbeddingOptions.builder()
                            .baseUrl(props.getInternal().getBaseUrl())
                            .apiKey(props.getInternal().getApiKey())
                            .model(e.getModel())
                            .build())
                    .build(), "internal:" + e.getModel());
            default -> throw new IllegalArgumentException("Неизвестный провайдер эмбеддингов: " + e.getProvider());
        };
    }
}
