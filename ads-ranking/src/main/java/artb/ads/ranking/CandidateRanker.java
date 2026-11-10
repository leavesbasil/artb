package artb.ads.ranking;

import java.util.List;

/**
 * Ранжирование спрятано за интерфейсом: в прототипе — простая реализация, потом адаптер
 * к боевому ранжировщику по доходности, CTR (клики / показы) и CR (действия в сценарии / показы).
 * Пустой список означает «без рекламы».
 */
public interface CandidateRanker {

    List<RankedCandidate> rank(List<JudgedCandidate> candidates, RankingContext ctx);
}
