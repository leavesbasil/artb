package artb.ads.delivery;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import artb.ads.campaign.model.Creative;
import artb.ads.matching.DialogMessage;
import artb.ads.matching.MatchOutcome;
import artb.ads.matching.MatchOutcome.Selection;
import artb.ads.matching.TurnMatcher;
import artb.ads.matching.TurnRequest;
import artb.ads.targeting.EligibleSetService;

/**
 * Подбор на ходе и выдача оффера. Каждое решение (и «без рекламы» тоже) пишется в ad_offer.
 * Выдача — не показ: показом считается только IMPRESSION от поверхности.
 */
public class AdServingService {

    public record MatchRequest(String sessionId, String clientId, int turnNo, List<DialogMessage> messages, Long deadlineMs) {}

    private final TurnMatcher matcher;
    private final OfferRepository offers;
    private final OfferTokenService tokens;
    private final EligibleSetService eligibleSets;
    private final DeliveryProperties props;
    private final Clock clock;

    public AdServingService(TurnMatcher matcher, OfferRepository offers, OfferTokenService tokens,
            EligibleSetService eligibleSets, DeliveryProperties props, Clock clock) {
        this.matcher = matcher;
        this.offers = offers;
        this.tokens = tokens;
        this.eligibleSets = eligibleSets;
        this.props = props;
        this.clock = clock;
    }

    /** Начало сессии: считает eligible set по сегментам клиента. Возвращает число допущенных кампаний. */
    public int startSession(String sessionId, String clientId) {
        return eligibleSets.forSession(sessionId, clientId).campaignIds().size();
    }

    public Optional<AdOfferView> serve(MatchRequest req) {
        Duration deadline = req.deadlineMs() != null ? Duration.ofMillis(req.deadlineMs()) : props.getDefaultDeadline();
        MatchOutcome out = matcher.match(new TurnRequest(req.sessionId(), req.clientId(), req.turnNo(), req.messages()), deadline);
        Instant now = clock.instant();
        UUID id = UUID.randomUUID();
        Optional<Selection> sel = out.selection();
        Instant expiresAt = sel.isPresent() ? now.plus(props.getOfferTtl()) : null;

        offers.insert(new OfferRecord(id, req.sessionId(), req.clientId(), req.turnNo(), now, expiresAt,
                sel.isPresent() ? OfferStatus.ISSUED : OfferStatus.NO_AD,
                out.intent() == null ? null : out.intent().queries(),
                out.intent() == null ? null : out.intent().commercialIntent(),
                out.intent() == null ? null : out.intent().topics(),
                out.candidates(),
                sel.map(s -> s.campaign().id()).orElse(null),
                sel.map(s -> s.creative().id()).orElse(null),
                sel.map(s -> s.format().name()).orElse(null),
                sel.map(Selection::relevance).orElse(null),
                sel.map(Selection::score).orElse(null),
                sel.map(Selection::reason).orElse(null),
                out.noAdReason() == null ? null : out.noAdReason().name(),
                out.fromCache(), out.latencyMs(), out.models()));

        return sel.map(s -> toView(s, tokens.sign(new OfferTokenService.OfferToken(id, req.sessionId(), expiresAt)), expiresAt));
    }

    private static AdOfferView toView(Selection s, String token, Instant expiresAt) {
        Creative c = s.creative();
        return new AdOfferView(token, expiresAt, s.format(), "Реклама", c.erid(),
                new AdOfferView.CreativeView(c.title(), c.body(), c.imageUrl(), c.cta(), c.url(), c.disclaimer(), c.questionText()),
                s.reason());
    }
}
