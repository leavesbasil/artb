package artb.ads.eval;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/** Метрики из дизайн-дока и таблица по кейсам в markdown. */
public final class EvalReport {

    private EvalReport() {
    }

    public static String render(List<EvalResult> results, Map<String, String> models) {
        List<EvalResult> withAd = results.stream().filter(r -> r.selectedKey() != null).toList();
        List<EvalResult> expectAd = results.stream().filter(r -> r.golden().expect().expectsAd()).toList();
        List<EvalResult> expectNoAd = results.stream().filter(r -> r.golden().expect().expectsNoAd()).toList();
        List<EvalResult> sensitive = results.stream().filter(r -> "sensitive".equals(r.golden().category())).toList();

        long correctShown = withAd.stream().filter(r -> r.golden().expect().expectsAd() && r.golden().expect().accepts(r.selectedKey())).count();
        long shownWhereExpected = withAd.stream().filter(r -> r.golden().expect().expectsAd()).count();
        List<Long> latencies = results.stream().filter(r -> r.error() == null).map(EvalResult::totalMs).sorted().toList();

        StringBuilder sb = new StringBuilder("# Golden set: отчёт\n\n");
        sb.append("Модели: ").append(models).append("\n\n");
        sb.append("| Метрика | Значение |\n| --- | --- |\n");
        row(sb, "Кейсов пройдено", count(results, EvalResult::passed) + " из " + results.size());
        row(sb, "precision@1 (показ среди допустимых, где показ ожидался)", ratio(correctShown, shownWhereExpected));
        row(sb, "Доля показов там, где реклама ожидалась", ratio(shownWhereExpected, expectAd.size()));
        row(sb, "Точность «без рекламы»", ratio(count(expectNoAd, EvalResult::passed), expectNoAd.size()));
        row(sb, "Показы на стоп-темах (должно быть 0)", String.valueOf(count(sensitive, r -> r.selectedKey() != null)));
        row(sb, "Полнота retrieval@20", ratio(count(expectAd, EvalResult::retrievedExpected), expectAd.size()));
        row(sb, "Ошибки", String.valueOf(count(results, r -> r.error() != null)));
        if (!latencies.isEmpty()) {
            row(sb, "Задержка подбора p50 / p95, мс", percentile(latencies, 50) + " / " + percentile(latencies, 95));
        }

        sb.append("\n## По категориям\n\n| Категория | Пройдено |\n| --- | --- |\n");
        results.stream().collect(Collectors.groupingBy(r -> r.golden().category()))
                .entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(e -> row(sb, e.getKey(), count(e.getValue(), EvalResult::passed) + " из " + e.getValue().size()));

        sb.append("\n## Кейсы\n\n| Кейс | Ожидание | Результат | Кандидаты (топ-3) | мс | |\n| --- | --- | --- | --- | --- | --- |\n");
        results.stream().sorted(Comparator.comparing((EvalResult r) -> r.golden().category()).thenComparing(r -> r.golden().id()))
                .forEach(r -> sb.append("| ").append(r.golden().id())
                        .append(" | ").append(expectation(r.golden().expect()))
                        .append(" | ").append(r.error() != null ? "ошибка: " + oneLine(r.error())
                                : r.selectedKey() != null ? r.selectedKey() + " (" + r.format() + ")" : "нет: " + r.noAdReason())
                        .append(" | ").append(String.join(", ", r.candidateKeys().subList(0, Math.min(3, r.candidateKeys().size()))))
                        .append(" | ").append(r.totalMs())
                        .append(" | ").append(r.passed() ? "✓" : "✗").append(" |\n"));
        return sb.toString();
    }

    private static String expectation(GoldenCase.Expect e) {
        if (e.expectsNoAd()) {
            return "без рекламы";
        }
        if (e.expectsAd()) {
            return e.campaign() + (e.acceptable() == null || e.acceptable().isEmpty() ? "" : " / " + String.join(", ", e.acceptable()));
        }
        return "не " + String.join(", ", e.forbidden());
    }

    private static String oneLine(String s) {
        String flat = s.replaceAll("\\s+", " ").replace("|", "/");
        return flat.length() > 120 ? flat.substring(0, 120) + "…" : flat;
    }

    private static void row(StringBuilder sb, String k, String v) {
        sb.append("| ").append(k).append(" | ").append(v).append(" |\n");
    }

    private static long count(List<EvalResult> list, Predicate<EvalResult> p) {
        return list.stream().filter(p).count();
    }

    private static String ratio(long a, long b) {
        return b == 0 ? "—" : String.format(Locale.ROOT, "%d / %d (%.0f%%)", a, b, 100.0 * a / b);
    }

    private static long percentile(List<Long> sorted, int p) {
        int idx = (int) Math.ceil(p / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, idx)));
    }
}
