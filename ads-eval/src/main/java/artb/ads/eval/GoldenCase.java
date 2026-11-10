package artb.ads.eval;

import java.util.List;
import java.util.Map;

import artb.ads.matching.DialogMessage;

/** Кейс golden set. Реплики — словари {client: …} или {agent: …}. */
public record GoldenCase(String id, String category, List<String> segments, List<Map<String, String>> dialog, Expect expect) {

    public record Expect(String campaign, List<String> acceptable, Boolean noAd, List<String> forbidden) {

        public boolean expectsAd() {
            return campaign != null;
        }

        public boolean expectsNoAd() {
            return Boolean.TRUE.equals(noAd);
        }

        public boolean accepts(String key) {
            return key.equals(campaign) || (acceptable != null && acceptable.contains(key));
        }

        public boolean forbids(String key) {
            return forbidden != null && forbidden.contains(key);
        }
    }

    public List<String> segmentsOrEmpty() {
        return segments == null ? List.of() : segments;
    }

    public List<DialogMessage> messages() {
        return dialog.stream().map(m -> m.containsKey("agent")
                ? DialogMessage.agent(m.get("agent"))
                : DialogMessage.client(m.get("client"))).toList();
    }
}
