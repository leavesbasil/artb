package artb.ads.targeting;

import java.util.Set;

import artb.ads.campaign.model.Campaign;

/** Допускает ли кампания клиента с данными бинарными сегментами. Белые списки — новой реализацией. */
public interface SegmentFilter {

    boolean isEligible(Campaign campaign, Set<String> clientSegments);
}
