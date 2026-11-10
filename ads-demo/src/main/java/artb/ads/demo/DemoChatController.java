package artb.ads.demo;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import artb.ads.campaign.model.Campaign;
import artb.ads.campaign.model.Creative;
import artb.ads.campaign.store.CampaignRepository;
import artb.ads.delivery.AdOfferView;
import artb.ads.delivery.AdServingService;
import artb.ads.delivery.AdServingService.MatchRequest;
import artb.ads.delivery.OfferRecord;
import artb.ads.delivery.OfferRepository;
import artb.ads.delivery.OfferTokenService;
import artb.ads.matching.DialogMessage;

/**
 * Демо-оркестратор агента: ответ агента и подбор рекламы запускаются параллельно на каждом ходе.
 * История диалога хранится в памяти процесса — это демо, не прод.
 */
@RestController
public class DemoChatController {

    public record ChatRequest(String sessionId, String clientId, String message, String fromOfferToken) {}

    public record ChatResponse(int turnNo, String answer, AdOfferView offer) {}

    private static final ExecutorService EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

    private final Map<String, List<DialogMessage>> dialogs = new ConcurrentHashMap<>();
    private final AgentService agent;
    private final AdServingService ads;
    private final OfferTokenService tokens;
    private final OfferRepository offers;
    private final CampaignRepository campaigns;

    public DemoChatController(AgentService agent, AdServingService ads, OfferTokenService tokens, OfferRepository offers,
            CampaignRepository campaigns) {
        this.agent = agent;
        this.ads = ads;
        this.tokens = tokens;
        this.offers = offers;
        this.campaigns = campaigns;
    }

    @PostMapping("/demo/sessions")
    public Map<String, Object> start(@RequestBody ChatRequest req) {
        dialogs.remove(req.sessionId());
        return Map.of("sessionId", req.sessionId(), "eligibleCampaigns", ads.startSession(req.sessionId(), req.clientId()));
    }

    @PostMapping("/demo/chat")
    public ChatResponse chat(@RequestBody ChatRequest req) {
        List<DialogMessage> history = dialogs.computeIfAbsent(req.sessionId(), k -> new ArrayList<>());
        List<DialogMessage> snapshot;
        int turnNo;
        synchronized (history) {
            history.add(DialogMessage.client(req.message()));
            snapshot = List.copyOf(history);
            turnNo = (int) snapshot.stream().filter(m -> m.role() == DialogMessage.Role.CLIENT).count();
        }
        Grounding grounding = grounding(req);

        CompletableFuture<String> answer = CompletableFuture.supplyAsync(
                () -> agent.answer(snapshot, grounding.campaign(), grounding.disclaimer()), EXECUTOR);
        CompletableFuture<Optional<AdOfferView>> offer = CompletableFuture.supplyAsync(
                () -> ads.serve(new MatchRequest(req.sessionId(), req.clientId(), turnNo, snapshot, null)), EXECUTOR)
                .exceptionally(e -> Optional.empty());

        String text = answer.join();
        synchronized (history) {
            history.add(DialogMessage.agent(text));
        }
        return new ChatResponse(turnNo, text, offer.join().orElse(null));
    }

    private record Grounding(Campaign campaign, String disclaimer) {}

    /** Клиент нажал рекламный вопрос: агент получает описание этой кампании. */
    private Grounding grounding(ChatRequest req) {
        if (req.fromOfferToken() == null) {
            return new Grounding(null, null);
        }
        return tokens.verify(req.fromOfferToken())
                .filter(t -> t.sessionId().equals(req.sessionId()))
                .flatMap(t -> offers.find(t.offerId()))
                .map(OfferRecord::campaignId)
                .flatMap(campaigns::find)
                .map(c -> new Grounding(c, campaigns.creatives(c.id()).stream()
                        .map(Creative::disclaimer).filter(d -> d != null && !d.isBlank()).findFirst().orElse(null)))
                .orElse(new Grounding(null, null));
    }
}
