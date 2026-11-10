package artb.ads.llm.masking;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RegexPiiMaskerTest {

    private final RegexPiiMasker masker = new RegexPiiMasker();

    @Test
    void masksContactsAndDocuments() {
        String masked = masker.mask("Мой телефон +7 (916) 123-45-67, почта ivan.petrov@mail.ru, "
                + "карта 4276 1234 5678 9012, счёт 40817810099910004312, паспорт 45 04 123456, "
                + "СНИЛС 112-233-445 95, ИНН 771234567890.");
        assertThat(masked)
                .contains("[ТЕЛЕФОН]", "[EMAIL]", "[КАРТА]", "[СЧЁТ]", "[ПАСПОРТ]", "[СНИЛС]", "ИНН [ИНН]")
                .doesNotContain("916", "ivan", "4276", "40817", "123456", "771234");
    }

    @Test
    void keepsAmountsAndDates() {
        String text = "Ремонт на 800 000 рублей, хочу взять до 15.12.2026 под 12%";
        assertThat(masker.mask(text)).isEqualTo(text);
    }
}
