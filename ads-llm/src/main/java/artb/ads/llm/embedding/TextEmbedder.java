package artb.ads.llm.embedding;

import java.util.List;

/** Эмбеддинги текста. Векторы нормированы, сходство — скалярное произведение (косинус). */
public interface TextEmbedder {

    float[] embed(String text);

    default List<float[]> embedAll(List<String> texts) {
        return texts.stream().map(this::embed).toList();
    }

    /** Идентификатор модели: пишется рядом с вектором, чтобы не смешивать векторы разных моделей. */
    String modelId();

    static double cosine(float[] a, float[] b) {
        double dot = 0;
        double na = 0;
        double nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        return na == 0 || nb == 0 ? 0 : dot / Math.sqrt(na * nb);
    }
}
