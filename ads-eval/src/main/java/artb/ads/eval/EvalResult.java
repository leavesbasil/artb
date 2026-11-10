package artb.ads.eval;

import java.util.List;

/** Результат одного кейса. */
public record EvalResult(
        GoldenCase golden,
        String selectedKey,
        String format,
        String noAdReason,
        List<String> candidateKeys,
        long totalMs,
        boolean fromCache,
        String error) {

    public boolean passed() {
        if (error != null) {
            return false;
        }
        GoldenCase.Expect e = golden.expect();
        if (selectedKey != null && e.forbids(selectedKey)) {
            return false;
        }
        if (e.expectsNoAd()) {
            return selectedKey == null;
        }
        if (e.expectsAd()) {
            return selectedKey != null && e.accepts(selectedKey);
        }
        return true;
    }

    /** Нужная кампания попала в кандидаты retrieval. */
    public boolean retrievedExpected() {
        GoldenCase.Expect e = golden.expect();
        return e.expectsAd() && candidateKeys.stream().anyMatch(e::accepts);
    }
}
