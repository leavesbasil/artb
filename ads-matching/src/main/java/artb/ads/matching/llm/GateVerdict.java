package artb.ads.matching.llm;

/** Ответ гейта: неуместно ли сейчас показывать рекламу по стадии диалога. */
public record GateVerdict(boolean inappropriate, String reason) {
}
