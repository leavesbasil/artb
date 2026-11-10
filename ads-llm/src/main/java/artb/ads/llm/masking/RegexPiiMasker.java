package artb.ads.llm.masking;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Маскирование по регулярным выражениям: email, телефоны, номера карт и счетов, паспорт, СНИЛС, ИНН.
 * ФИО регулярками надёжно не ловятся — для этого нужен NER-сервис внутри контура, который
 * подключается своей реализацией {@link PiiMasker}.
 */
public class RegexPiiMasker implements PiiMasker {

    private record Rule(Pattern pattern, String replacement) {}

    private static final List<Rule> RULES = List.of(
            new Rule(Pattern.compile("[\\w.+-]+@[\\w-]+(\\.[\\w-]+)+"), "[EMAIL]"),
            new Rule(Pattern.compile("(?iu)ИНН\\s*:?\\s*\\d{10}(\\d{2})?"), "ИНН [ИНН]"),
            new Rule(Pattern.compile("\\b\\d{3}-\\d{3}-\\d{3}[ -]?\\d{2}\\b"), "[СНИЛС]"),
            new Rule(Pattern.compile("\\b\\d{20}\\b"), "[СЧЁТ]"),
            new Rule(Pattern.compile("\\b(?:\\d{4}[ -]?){3}\\d{4}(?:\\d{3})?\\b"), "[КАРТА]"),
            new Rule(Pattern.compile("(?<!\\d)(?:\\+7|8)[\\s(-]*\\d{3}[\\s)-]*\\d{3}[\\s-]*\\d{2}[\\s-]*\\d{2}(?!\\d)"), "[ТЕЛЕФОН]"),
            new Rule(Pattern.compile("\\b\\d{2}\\s?\\d{2}\\s?\\d{6}\\b"), "[ПАСПОРТ]"));

    @Override
    public String mask(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String result = text;
        for (Rule rule : RULES) {
            result = rule.pattern().matcher(result).replaceAll(rule.replacement());
        }
        return result;
    }
}
