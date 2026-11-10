package artb.ads.campaign;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import artb.ads.campaign.ingest.CreativeValidator;
import artb.ads.campaign.model.Creative;

class CreativeValidatorTest {

    private final CreativeValidator validator = new CreativeValidator();
    private final UUID id = UUID.randomUUID();

    @Test
    void rateWithoutDisclaimerIsRejected() {
        Creative card = Creative.card(id, "Кредит", "Ставка от 9,9% годовых", null, "Оформить", "https://x", null, null);
        assertThat(validator.validate(card)).anyMatch(e -> e.contains("дисклеймер"));
    }

    @Test
    void rateWithDisclaimerPasses() {
        Creative card = Creative.card(id, "Кредит", "Ставка от 9,9% годовых", null, "Оформить", "https://x",
                "Ставка при сумме от 300 тыс. ₽ и сроке до 5 лет", "12345");
        assertThat(validator.validate(card)).isEmpty();
    }

    @Test
    void questionWithoutCostTermsPasses() {
        assertThat(validator.validate(Creative.question(id, "Сколько я сэкономлю, если объединю кредиты?"))).isEmpty();
    }

    @Test
    void cardNeedsTitleAndLink() {
        Creative card = Creative.card(id, null, "Текст", null, "Кнопка", null, null, null);
        assertThat(validator.validate(card)).hasSize(2);
    }
}
