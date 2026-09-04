package gay.runescape.runeparty;

/** One entry in a MINIGAME_ENDED payload's "results" list -- see Json#safeMinigameScores. Distinct
 * from MinigameReward (that one's "payouts", coins actually paid out) since a player can score
 * without earning anything (e.g. everyone but the top scorer in a pay_out_top mini-game). */
public class MinigameScore
{
    public final String rsn;
    public final int score;

    public MinigameScore(String rsn, int score)
    {
        this.rsn = rsn;
        this.score = score;
    }
}
