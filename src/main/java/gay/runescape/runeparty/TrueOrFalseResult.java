package gay.runescape.runeparty;

/** One player's outcome on a single True or False round -- see TRUE_OR_FALSE_ROUND_ENDED's own
 * "results" list and AnnouncementOverlay#renderTrueOrFalseReveal, the only consumer. {@code answer}
 * is null if they never answered in time (always incorrect in that case). */
public class TrueOrFalseResult
{
    public final String rsn;
    public final Boolean answer;
    public final boolean correct;

    public TrueOrFalseResult(String rsn, Boolean answer, boolean correct)
    {
        this.rsn = rsn;
        this.answer = answer;
        this.correct = correct;
    }
}
