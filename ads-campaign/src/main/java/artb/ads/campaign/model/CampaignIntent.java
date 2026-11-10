package artb.ads.campaign.model;

import java.util.UUID;

public record CampaignIntent(UUID id, UUID campaignId, String text, IntentPolarity polarity, boolean approved) {

    public static CampaignIntent draft(UUID campaignId, String text, IntentPolarity polarity) {
        return new CampaignIntent(UUID.randomUUID(), campaignId, text, polarity, false);
    }

    public CampaignIntent approve() {
        return new CampaignIntent(id, campaignId, text, polarity, true);
    }
}
