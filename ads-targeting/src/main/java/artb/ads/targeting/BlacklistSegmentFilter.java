package artb.ads.targeting;

import java.util.Collections;
import java.util.Set;

import artb.ads.campaign.model.Campaign;

/** Прототип: кампания не показывается клиентам из сегментов её чёрного списка. */
public class BlacklistSegmentFilter implements SegmentFilter {

    @Override
    public boolean isEligible(Campaign campaign, Set<String> clientSegments) {
        return Collections.disjoint(campaign.excludedSegments(), clientSegments);
    }
}
