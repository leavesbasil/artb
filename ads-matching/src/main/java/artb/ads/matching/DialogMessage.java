package artb.ads.matching;

public record DialogMessage(Role role, String text) {

    public enum Role { CLIENT, AGENT }

    public static DialogMessage client(String text) {
        return new DialogMessage(Role.CLIENT, text);
    }

    public static DialogMessage agent(String text) {
        return new DialogMessage(Role.AGENT, text);
    }
}
