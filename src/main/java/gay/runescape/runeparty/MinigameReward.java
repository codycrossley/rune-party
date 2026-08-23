package gay.runescape.runeparty;

/** One entry in a MINIGAME_ENDED payload's "payouts" list -- see Json#safeMinigameRewards. */
public class MinigameReward
{
    public final String rsn;
    public final int coins;

    public MinigameReward(String rsn, int coins)
    {
        this.rsn = rsn;
        this.coins = coins;
    }
}
