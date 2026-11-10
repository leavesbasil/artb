package artb.ads.llm.masking;

/** Маскирование персональных данных перед отправкой текста в любую модель. */
public interface PiiMasker {

    String mask(String text);
}
