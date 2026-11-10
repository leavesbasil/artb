package artb.ads.llm.embedding;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Детерминированные «эмбеддинги» без модели: хэширование слов, коротких основ и символьных триграмм.
 * Ловят лексическую близость с учётом русских окончаний, но не смысл. Нужны для тестов и локальной
 * отладки без скачивания моделей; в проде — модель эмбеддингов внутри контура.
 */
public class HashingTextEmbedder implements TextEmbedder {

    private static final Pattern NON_LETTERS = Pattern.compile("[^\\p{L}\\p{Nd}]+");
    private static final int STEM_LENGTH = 5;

    private final int dimensions;

    public HashingTextEmbedder(int dimensions) {
        this.dimensions = dimensions;
    }

    @Override
    public float[] embed(String text) {
        float[] v = new float[dimensions];
        String normalized = text.toLowerCase(Locale.ROOT).replace('ё', 'е');
        for (String word : NON_LETTERS.split(normalized)) {
            if (word.length() < 2) {
                continue;
            }
            add(v, "w:" + word, 1.0f);
            if (word.length() > STEM_LENGTH) {
                add(v, "s:" + word.substring(0, STEM_LENGTH), 1.0f);
            } else {
                add(v, "s:" + word, 1.0f);
            }
            String padded = "^" + word + "$";
            for (int i = 0; i + 3 <= padded.length(); i++) {
                add(v, "t:" + padded.substring(i, i + 3), 0.35f);
            }
        }
        normalize(v);
        return v;
    }

    @Override
    public String modelId() {
        return "hashing-" + dimensions;
    }

    private void add(float[] v, String feature, float weight) {
        int h = murmur(feature.getBytes(StandardCharsets.UTF_8));
        int index = Math.floorMod(h, dimensions);
        float sign = ((h >>> 31) == 0) ? 1f : -1f;
        v[index] += sign * weight;
    }

    private static void normalize(float[] v) {
        double norm = 0;
        for (float x : v) {
            norm += x * x;
        }
        if (norm == 0) {
            return;
        }
        float inv = (float) (1.0 / Math.sqrt(norm));
        for (int i = 0; i < v.length; i++) {
            v[i] *= inv;
        }
    }

    /** MurmurHash3 x86_32, seed 0x9747b28c. */
    private static int murmur(byte[] data) {
        int h = 0x9747b28c;
        int len = data.length;
        int i = 0;
        while (len - i >= 4) {
            int k = (data[i] & 0xff) | (data[i + 1] & 0xff) << 8 | (data[i + 2] & 0xff) << 16 | (data[i + 3] & 0xff) << 24;
            k *= 0xcc9e2d51;
            k = Integer.rotateLeft(k, 15);
            k *= 0x1b873593;
            h ^= k;
            h = Integer.rotateLeft(h, 13);
            h = h * 5 + 0xe6546b64;
            i += 4;
        }
        int k = 0;
        switch (len - i) {
            case 3: k ^= (data[i + 2] & 0xff) << 16;
            case 2: k ^= (data[i + 1] & 0xff) << 8;
            case 1:
                k ^= data[i] & 0xff;
                k *= 0xcc9e2d51;
                k = Integer.rotateLeft(k, 15);
                k *= 0x1b873593;
                h ^= k;
            default:
        }
        h ^= len;
        h ^= h >>> 16;
        h *= 0x85ebca6b;
        h ^= h >>> 13;
        h *= 0xc2b2ae35;
        h ^= h >>> 16;
        return h;
    }
}
