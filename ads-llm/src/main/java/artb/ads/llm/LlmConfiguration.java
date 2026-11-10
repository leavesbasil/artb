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
    StageModels stageModels(LlmProperties props) {
        return new StageModels(props);
    }

    @Bean
    @ConditionalOnMissingBean
    LlmGateway llmGateway(StageModels models, PiiMasker masker, LlmProperties props) {
        return new DefaultLlmGateway(models, masker, props.getMasking().isEnabled());
    }

    @Bean
    @ConditionalOnMissingBean
    TextEmbedder textEmbedder(LlmProperties props) {
        LlmProperties.Embedding e = props.getEmbedding();
        return switch (e.getProvider()) {
            case "hashing" -> new HashingTextEmbedder(e.getDimensions());
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
