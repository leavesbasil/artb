package artb.ads.llm.embedding;

import java.util.List;

import org.springframework.ai.embedding.EmbeddingModel;

/** Адаптер к любому {@link EmbeddingModel} из Spring AI (в проде — сервер эмбеддингов внутри контура). */
public class SpringAiTextEmbedder implements TextEmbedder {

    private final EmbeddingModel model;
    private final String modelId;

    public SpringAiTextEmbedder(EmbeddingModel model, String modelId) {
        this.model = model;
        this.modelId = modelId;
    }

    @Override
    public float[] embed(String text) {
        return model.embed(text);
    }

    @Override
    public List<float[]> embedAll(List<String> texts) {
        return model.embed(texts);
    }

    @Override
    public String modelId() {
        return modelId;
    }
}
