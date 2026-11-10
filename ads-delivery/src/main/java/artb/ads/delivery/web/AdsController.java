package artb.ads.delivery.web;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import artb.ads.delivery.AdOfferView;
import artb.ads.delivery.AdServingService;
import artb.ads.delivery.AdServingService.MatchRequest;
import artb.ads.delivery.EventService;
import artb.ads.delivery.EventService.EventRequest;
import artb.ads.delivery.EventService.EventResult;
import artb.ads.delivery.OfferRecord;
import artb.ads.delivery.OfferRepository;

@RestController
public class AdsController {

    public record SessionRequest(String sessionId, String clientId) {}

    private final AdServingService serving;
    private final EventService events;
    private final OfferRepository offers;

    public AdsController(AdServingService serving, EventService events, OfferRepository offers) {
        this.serving = serving;
        this.events = events;
        this.offers = offers;
    }

    @PostMapping("/ads/sessions")
    public Map<String, Object> startSession(@RequestBody SessionRequest req) {
        return Map.of("sessionId", req.sessionId(), "eligibleCampaigns", serving.startSession(req.sessionId(), req.clientId()));
    }

    /** AdOffer или 204, если на этом ходе рекламы нет. */
    @PostMapping("/ads/match")
    public ResponseEntity<AdOfferView> match(@RequestBody MatchRequest req) {
        return serving.serve(req).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.noContent().build());
    }

    @PostMapping("/ads/events")
    public ResponseEntity<EventResult> event(@RequestBody EventRequest req) {
        EventResult r = events.record(req);
        HttpStatus status = switch (r.result()) {
            case ACCEPTED -> HttpStatus.ACCEPTED;
            case DUPLICATE -> HttpStatus.OK;
            case REJECTED -> HttpStatus.UNPROCESSABLE_CONTENT;
        };
        return ResponseEntity.status(status).body(r);
    }

    @GetMapping("/debug/offers")
    public List<OfferRecord> debugOffers(@RequestParam(required = false) String sessionId) {
        return sessionId == null ? offers.recent(50) : offers.bySession(sessionId);
    }
}
