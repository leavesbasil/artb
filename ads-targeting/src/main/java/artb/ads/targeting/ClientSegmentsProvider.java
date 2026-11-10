package artb.ads.targeting;

import java.util.Set;

/** Бинарные сегменты клиента. Знаются не из диалога и никогда не уходят в LLM. */
public interface ClientSegmentsProvider {

    Set<String> segments(String clientId);
}
