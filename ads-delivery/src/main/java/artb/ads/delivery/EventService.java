package artb.ads.delivery;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

import artb.ads.delivery.OfferTokenService.OfferToken;

/**
 * События от поверхности. IMPRESSION переводит оффер в SHOWN, если он не истёк. Клик или скрытие
 * без IMPRESSION считаются неявным показом: события могут прийти не по порядку.
 */
public class EventService {

    public record EventRequest(String offerToken, EventType type, Instant occurredAt, String surface) {}

    public enum Result { ACCEPTED, DUPLICATE, REJECTED }

    public record EventResult(Result result, String message) {
        static EventResult rejected(String message) {
            return new EventResult(Result.REJECTED, message);
        }
    }

    private final OfferRepository offers;
    private final OfferTokenService tokens;
    private final DeliveryProperties props;
    private final Clock clock;

    public EventService(OfferRepository offers, OfferTokenService tokens, DeliveryProperties props, Clock clock) {
        this.offers = offers;
        this.tokens = tokens;
        this.props = props;
        this.clock = clock;
    }

    public EventResult record(EventRequest req) {
        if (req.type() == null) {
            return EventResult.rejected("не указан тип события");
        }
        Optional<OfferToken> token = tokens.verify(req.offerToken());
        if (token.isEmpty()) {
            return EventResult.rejected("неверная подпись токена");
        }
        Optional<OfferRecord> found = offers.find(token.get().offerId());
        if (found.isEmpty() || !found.get().sessionId().equals(token.get().sessionId())) {
            return EventResult.rejected("оффер не найден");
        }
        if (req.type() == EventType.CONVERSION) {
            return EventResult.rejected("конверсии в прототипе не принимаются: они приходят фоновыми потоками");
        }
        OfferRecord offer = found.get();
        Instant now = clock.instant();
        Instant occurredAt = req.occurredAt() != null ? req.occurredAt() : now;

        if (req.type() == EventType.IMPRESSION || req.type().impliesImpression()) {
            boolean shown = offer.status() == OfferStatus.SHOWN;
            if (!shown && (offer.status() != OfferStatus.ISSUED || !now.isBefore(offer.expiresAt()))) {
                return EventResult.rejected("оффер истёк без показа");
            }
            if (shown && req.type() != EventType.IMPRESSION
                    && now.isAfter(offer.createdAt().plus(props.getInteractionWindow()))) {
                return EventResult.rejected("окно взаимодействия закрыто");
            }
            boolean impressionInserted = offers.insertEvent(offer.id(), EventType.IMPRESSION, occurredAt, now, req.surface());
            if (impressionInserted) {
                offers.updateStatus(offer.id(), OfferStatus.ISSUED, OfferStatus.SHOWN);
            }
            if (req.type() == EventType.IMPRESSION) {
                return new EventResult(impressionInserted ? Result.ACCEPTED : Result.DUPLICATE, null);
            }
        }
        boolean inserted = offers.insertEvent(offer.id(), req.type(), occurredAt, now, req.surface());
        return new EventResult(inserted ? Result.ACCEPTED : Result.DUPLICATE, null);
    }
}
