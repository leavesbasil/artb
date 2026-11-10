package artb.ads.campaign.ingest;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import artb.ads.campaign.model.AdFormat;
import artb.ads.campaign.model.Creative;

/**
 * Правила для креативов. Ст. 28 ФЗ «О рекламе»: если в рекламе финансовой услуги есть ставка или
 * сумма, должны быть указаны все условия, влияющие на стоимость, — здесь это требование дисклеймера.
 * Проверка — правилом, без LLM.
 */
public class CreativeValidator {

    private static final Pattern COST_TERMS = Pattern.compile(
            "(?iu)\\d+([.,]\\d+)?\\s*%|ставк|\\bруб|₽|\\d+\\s*(тыс|млн)|процент|переплат|ежемесячн\\w*\\s+плат");

    public List<String> validate(Creative c) {
        List<String> errors = new ArrayList<>();
        if (c.type() == AdFormat.CARD) {
            require(errors, c.title(), "у карточки нет заголовка");
            require(errors, c.body(), "у карточки нет текста");
            require(errors, c.cta(), "у карточки нет CTA");
            require(errors, c.url(), "у карточки нет ссылки");
        } else {
            require(errors, c.questionText(), "у вопроса нет текста");
        }
        if (COST_TERMS.matcher(c.fullText()).find() && isBlank(c.disclaimer())) {
            errors.add("в креативе есть ставка или сумма — нужен дисклеймер со всеми условиями (ст. 28 ФЗ «О рекламе»)");
        }
        return errors;
    }

    private static void require(List<String> errors, String value, String message) {
        if (isBlank(value)) {
            errors.add(message);
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
