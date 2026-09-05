package gay.runescape.runeparty.net;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import gay.runescape.runeparty.TrueOrFalseResult;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.coords.WorldPoint;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Every payload-field reader used to fold an event's JsonObject payload into client state.
 * <p>
 * {@code safeStr}/{@code safeInt} silently return null on a missing/null field -- the right
 * default for a field a caller already null-guards or has a sensible fallback for.
 * {@code requiredStr}/{@code requiredInt} do the same, but log a warning when the field is
 * actually missing, for reads where that means something's out of sync between client and server
 * rather than a normal empty case. eventType makes that warning actionable (which event, which
 * field). */
@Slf4j
public final class Json
{
    private Json() {}

    public static String safeStr(JsonObject o, String key)
    {
        return (o != null && o.has(key) && !o.get(key).isJsonNull()) ? o.get(key).getAsString() : null;
    }

    public static Integer safeInt(JsonObject o, String key)
    {
        try { return (o != null && o.has(key) && !o.get(key).isJsonNull()) ? o.get(key).getAsInt() : null; }
        catch (Exception ignored) { return null; }
    }

    /** Same as {@link #safeStr}, but logs a warning if {@code key} is missing/null -- use this at
     * a read the existing code already treats as required (a null-guard right after the read). */
    public static String requiredStr(JsonObject o, String eventType, String key)
    {
        String v = safeStr(o, key);
        if (v == null) log.warn("{} payload missing required field \"{}\"", eventType, key);
        return v;
    }

    /** Same as {@link #safeInt}, but logs a warning if {@code key} is missing/null -- see
     * {@link #requiredStr}'s own doc on when to reach for this instead of the plain variant. */
    public static Integer requiredInt(JsonObject o, String eventType, String key)
    {
        Integer v = safeInt(o, key);
        if (v == null) log.warn("{} payload missing required field \"{}\"", eventType, key);
        return v;
    }

    /** Reads a nested {@code {x, y, plane}} object. Null if the key's missing or any of the three
     * fields is. */
    public static WorldPoint safeWorldPoint(JsonObject o, String key)
    {
        if (o == null || !o.has(key) || !o.get(key).isJsonObject()) return null;
        JsonObject p = o.getAsJsonObject(key);
        Integer x = safeInt(p, "x");
        Integer y = safeInt(p, "y");
        Integer plane = safeInt(p, "plane");
        if (x == null || y == null || plane == null) return null;
        return new WorldPoint(x, y, plane);
    }

    /** Reads DICE_ROLLED's targetIndices -- plural since a fork can offer more than one candidate
     * destination for a single roll. Never null, only empty. */
    public static List<Integer> safeIntList(JsonObject o, String key)
    {
        if (o == null || !o.has(key) || o.get(key).isJsonNull() || !o.get(key).isJsonArray()) return Collections.emptyList();
        JsonArray arr = o.get(key).getAsJsonArray();
        List<Integer> out = new ArrayList<>(arr.size());
        for (int i = 0; i < arr.size(); i++)
        {
            try { out.add(arr.get(i).getAsInt()); }
            catch (Exception ignored) { /* skip malformed entry */ }
        }
        return out;
    }

    /** Same shape as {@link #safeIntList}, returning a primitive array instead. One malformed
     * element discards the whole array rather than skipping just that entry (unlike
     * safeIntList). */
    public static int[] safeIntArray(JsonObject o, String key)
    {
        if (o == null || !o.has(key) || o.get(key).isJsonNull() || !o.get(key).isJsonArray()) return new int[0];
        JsonArray arr = o.get(key).getAsJsonArray();
        int[] out = new int[arr.size()];
        for (int i = 0; i < arr.size(); i++)
        {
            try { out[i] = arr.get(i).getAsInt(); }
            catch (Exception ignored) { return new int[0]; }
        }
        return out;
    }

    /** Reads a JsonArray field, or an empty JsonArray if missing/null/not-an-array. */
    public static JsonArray safeArray(JsonObject o, String key)
    {
        return (o != null && o.has(key) && o.get(key).isJsonArray()) ? o.get(key).getAsJsonArray() : new JsonArray();
    }

    /** Reads MINIGAME_ENDED's "payouts" list -- {@code [{"player": rsn, "coins": int}, ...]} --
     * one entry per player the mini-game decided to reward; players who got nothing simply aren't
     * in the list. Never null, only empty. */
    public static List<MinigameReward> safeMinigameRewards(JsonObject o, String key)
    {
        if (o == null || !o.has(key) || o.get(key).isJsonNull() || !o.get(key).isJsonArray()) return Collections.emptyList();
        JsonArray arr = o.get(key).getAsJsonArray();
        List<MinigameReward> out = new ArrayList<>(arr.size());
        for (int i = 0; i < arr.size(); i++)
        {
            try
            {
                JsonObject entry = arr.get(i).getAsJsonObject();
                String rsn = safeStr(entry, "player");
                Integer coins = safeInt(entry, "coins");
                if (rsn != null && coins != null) out.add(new MinigameReward(rsn, coins));
            }
            catch (Exception ignored) { /* skip malformed entry */ }
        }
        return out;
    }

    /** Reads MINIGAME_ENDED's "results" list -- {@code [{"player": rsn, "score": int}, ...]} --
     * one entry per player the mini-game's own pay_out/pay_out_flat/pay_out_top scored, regardless
     * of whether that score actually earned them anything (see MinigameScore's own doc). Never
     * null, only empty. */
    public static List<MinigameScore> safeMinigameScores(JsonObject o, String key)
    {
        if (o == null || !o.has(key) || o.get(key).isJsonNull() || !o.get(key).isJsonArray()) return Collections.emptyList();
        JsonArray arr = o.get(key).getAsJsonArray();
        List<MinigameScore> out = new ArrayList<>(arr.size());
        for (int i = 0; i < arr.size(); i++)
        {
            try
            {
                JsonObject entry = arr.get(i).getAsJsonObject();
                String rsn = safeStr(entry, "player");
                Integer score = safeInt(entry, "score");
                if (rsn != null && score != null) out.add(new MinigameScore(rsn, score));
            }
            catch (Exception ignored) { /* skip malformed entry */ }
        }
        return out;
    }

    /** Parses a TRUE_OR_FALSE_ROUND_ENDED payload's "results" list. {@code answer} is nullable
     * (missing/null JSON means the player never answered that round at all, not that they
     * answered false). */
    public static List<TrueOrFalseResult> safeTrueOrFalseResults(JsonObject o)
    {
        if (o == null || !o.has("results") || o.get("results").isJsonNull() || !o.get("results").isJsonArray()) return Collections.emptyList();
        JsonArray arr = o.get("results").getAsJsonArray();
        List<TrueOrFalseResult> out = new ArrayList<>(arr.size());
        for (int i = 0; i < arr.size(); i++)
        {
            try
            {
                JsonObject entry = arr.get(i).getAsJsonObject();
                String rsn = safeStr(entry, "player");
                if (rsn == null) continue;
                JsonElement answerEl = entry.get("answer");
                Boolean answer = answerEl != null && !answerEl.isJsonNull() ? answerEl.getAsBoolean() : null;
                JsonElement correctEl = entry.get("correct");
                boolean correct = correctEl != null && !correctEl.isJsonNull() && correctEl.getAsBoolean();
                out.add(new TrueOrFalseResult(rsn, answer, correct));
            }
            catch (Exception ignored) { /* skip malformed entry */ }
        }
        return out;
    }
}
