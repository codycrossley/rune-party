package gay.runescape.runeparty;

public interface EventListener
{
    void onEvent(ApiClient.EventOut e);
    void onError(Exception e);
}
