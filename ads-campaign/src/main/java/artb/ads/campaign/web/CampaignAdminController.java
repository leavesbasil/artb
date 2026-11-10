package artb.ads.campaign.web;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import artb.ads.campaign.ingest.CampaignService;
import artb.ads.campaign.ingest.CampaignService.ApproveRequest;
import artb.ads.campaign.ingest.CampaignService.CreateRequest;
import artb.ads.campaign.model.Campaign;
import artb.ads.campaign.model.CampaignIntent;
import artb.ads.campaign.model.Creative;
import artb.ads.campaign.store.CampaignRepository;

@RestController
@RequestMapping("/admin/campaigns")
public class CampaignAdminController {

    public record CampaignView(Campaign campaign, List<Creative> creatives, List<CampaignIntent> intents) {}

    private final CampaignService service;
    private final CampaignRepository repo;

    public CampaignAdminController(CampaignService service, CampaignRepository repo) {
        this.service = service;
        this.repo = repo;
    }

    /** Черновик + LLM-обогащение. Вызов синхронный: обогащение занимает секунды. */
    @PostMapping
    public ResponseEntity<CampaignView> create(@RequestBody CreateRequest req) {
        Campaign c = service.createAndEnrich(req);
        return ResponseEntity.status(HttpStatus.CREATED).body(view(c.id()));
    }

    @GetMapping
    public List<Campaign> list() {
        return repo.findAll(100);
    }

    @GetMapping("/{id}")
    public CampaignView get(@PathVariable UUID id) {
        return view(id);
    }

    @PostMapping("/{id}/approve")
    public CampaignView approve(@PathVariable UUID id, @RequestBody ApproveRequest req) {
        service.approve(id, req);
        return view(id);
    }

    @PostMapping("/{id}/pause")
    public CampaignView pause(@PathVariable UUID id) {
        service.pause(id);
        return view(id);
    }

    private CampaignView view(UUID id) {
        Campaign c = repo.find(id).orElseThrow(() -> new CampaignService.NotFoundException("кампания " + id));
        return new CampaignView(c, repo.creatives(id), repo.intents(id));
    }

    @ExceptionHandler(CampaignService.ValidationException.class)
    ResponseEntity<Map<String, Object>> invalid(CampaignService.ValidationException e) {
        return ResponseEntity.unprocessableContent().body(Map.of("errors", e.errors()));
    }

    @ExceptionHandler(CampaignService.NotFoundException.class)
    ResponseEntity<Map<String, Object>> notFound(CampaignService.NotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
    }
}
