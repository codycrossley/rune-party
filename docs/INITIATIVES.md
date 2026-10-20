# Initiatives

A high-level tracker for multi-step efforts that span more than one session or commit — status and
the short version of *why*, not a line-by-line log. `CHANGELOG.md` has the day-to-day feature record;
`DECISIONS.md` has the retrospective writeup for anything here that lands as a permanent
architectural pattern. Newest first.

## World-visible mini-game reveals audited for the "before the banner" race

**Status:** fixed, 5 confirmed sites; audited the rest of the client and found no others.

**The bug report:** in a live production game, an item-space grant played out, and immediately
after -- before the "MINIGAME!" banner ever appeared -- the two Jad models for "Who's Your
Jaddy?" were already standing on the board. The cosmetic announcement caught up only afterward.

**Root cause:** the server applies a mini-game's board swap as real state the instant
`MINIGAME_STARTED` fires. The client folds that immediately too (correct -- a client that's
reconnecting mid-mini-game needs the board to already look right). But AnnouncementOverlay's own
"MINIGAME!" banner is deliberately *delayed*, via `scheduleAfterTurnEffects`, behind whatever else
happened to be occupying the shared turn-effect gate at that exact moment -- in this case, the
item-pickup reveal that had just played. Normally that gate is empty when a mini-game starts and
the race is imperceptible; an item reveal (or any other still-settling effect) stretches it out
just enough to expose it.

**Turns out the fix already existed -- just applied inconsistently.**
`RunePartyPlugin.isMinigameSelectionRevealed()` is a pre-existing, explicitly-documented flag for
exactly this: true once the selection wheel has actually spun to a stop for a live client (or
immediately, for one that's catching up). Its own doc already says outright: *"Any board-state
change that's specific to which mini-game got picked... should gate on this rather than
isMinigameActive()/isXActive() alone."* `TileOverlay`'s Rainbow Rush course recolor and its whole
`isMinigameSelectionPending()` model-sync/tile-filter mechanism already follow this correctly, and
so does `DanceDanceRuneScapeOverlay` and two of three `PlayerOverlay` Brutus Attack branches. The
other five call sites just never got the memo:

- `JaddyDuelModel.render()` -- spawns the two Jad NPCs straight off a live `TileReducer` scan,
  gated only on `GamePhase.ACTIVE`. The actual reported bug.
- `CrabRaveNpcOverlay.render()` -- same shape, for Crab Rave's dance NPCs.
- `PlayerTransformOverlay.render()` -- transforms a player into Brutus's giant model, gated only
  on `isBrutusAttackActive()`.
- `PlayerOverlay`'s Turf Wars team-color branch (`isTurfWarsActive()` alone).
- `PlayerOverlay`'s Who's Your Jaddy zone-color branch (`isJaddyActive()` alone) -- right next to
  the two Brutus Attack branches in the very same method that already had this correct, which is
  its own small proof of how this kind of gap creeps in: get it right once, then the next
  mini-game added nearby doesn't automatically inherit it.

Fix: added `&& plugin.isMinigameSelectionRevealed()` (or the inverted `|| !...` form for an
early-return guard) at each, mirroring `DanceDanceRuneScapeOverlay`'s already-correct template
exactly -- no new flag, no architecture change.

**Verified NOT affected**, so this doesn't read as broader than it is: every `*HudOverlay`/
`*Overlay` for Sandwich Rush, Fishing Contest, Click Click Click, Hot Potato, Turf Wars (score),
Rune Match, Coin Rush, DDR's own HUD -- all already gate on `isMinigamePlayable()`, which requires
the ready-check *and* countdown to have finished, strictly later than selection-reveal and so
never premature. Every per-tile render inside `TileOverlay#renderCommittedCourse` (arena outlines,
`arenaFireModel`/`tableModel`/`pondModel`) reads from the SAME `entries` list that's already
filtered down to exclude every mini-game-only tile type while selection is pending -- so those
`isXActive()`-alone checks there are redundant-but-harmless early exits, not bugs. Brutus Attack's
crash-zone highlight and dash countdown/result, and Rainbow Rush's start arrow, are all stamped
off round-begin-or-later events that can't fire before a round has genuinely started. `JadEncounter`
(the solo awakening event, unrelated to Who's Your Jaddy) has its own dedicated timed reveal, not
board-state-driven at all.

## AnnouncementOverlay text re-anchored to the viewport, not the canvas

**Status:** done.

**The problem:** the screen-fit work above computed everything from `client.getCanvasWidth()`/
`getCanvasHeight()` -- the full RuneLite drawing surface. In OSRS's Fixed layouts (and some
Resizable ones), the actual 3D game viewport is a *smaller sub-rectangle* of that canvas -- the
inventory, chat, and minimap widgets occupy the rest of it. Centering text on the raw canvas put it
partly or entirely behind those widgets whenever the viewport didn't fill the canvas, which the
earlier fix didn't address since it only ever asked "does this fit the canvas," not "is this
where the visible game world actually is."

**The fix:** two new helpers, `drawableWidth()`/`drawableHeight()`, read
`client.getViewportWidth()`/`getViewportHeight()` instead -- the real 3D viewport's own size, not
the canvas's. Every `centerX = .../2` and `y = .../3` position formula in the file (over 100 call
sites) now reads from these instead of the canvas accessors directly, so text is laid out relative
to what's actually visible, not what's nominally in the window.

Rather than rewrite each of those 100+ formulas to add an explicit viewport origin offset,
`drawableWidth()`/`drawableHeight()` fold the offset in algebraically: `viewportWidth + 2 *
viewportXOffset`. Since the viewport always sits flush against the canvas's own top-left corner in
practice (the widgets eat into its right/bottom edges, not its left/top), that makes
`drawableWidth() / 2` land exactly on the viewport's true center -- the overwhelmingly common case,
every plain `centerX` in the file -- and leaves only a couple of px of error for the handful of
non-center vertical fractions (`/3`, `/4`) elsewhere. A mechanical, low-risk substitution across the
whole file instead of 100+ individual rewrites.

`safeTextWidth()` (the shrink/wrap-fit budget from the entry below) now derives from
`drawableWidth()` too, so a line that already fit the canvas but didn't fit the actual viewport now
correctly wraps/shrinks instead of running behind a widget.

Scope: only `AnnouncementOverlay` needed this. `RunePartyMapOverlay`'s full-screen course map,
`ChatboxDialogueOverlay`'s dialogue box (which deliberately mimics/replaces the native chatbox
widget's own screen position), and `ConfettiOverlay`'s decorative particle burst all have reasons
to intentionally use the full canvas and were left alone.

## Side panel roster rows split into two lines

**Status:** done.

**The problem:** `RunePartyPanel`'s player roster used a `GridLayout(1, 5)` -- "#", "Player",
"Coins", "Golden Gnomes", "Items" all forced to equal-width columns. That gave "Player" the same
width as "Items", so a longer RSN just got clipped at the column boundary.

**The fix:** each player is now two stacked lines instead of one row of five columns -- a
`BorderLayout` line with the seat number and RSN (RSN gets the whole rest of the panel's width,
nothing competing for it), then a plain "Coins: N   GG: N   Items: N" line underneath. No more
column header (the existing "Players" section caption above the table already gives context) --
there's nothing left to align into columns once stats aren't laid out side by side with the name.
Considered icon-based stats (reusing the coin/golden-gnome PNGs already bundled for the Chance
Tile overlay) and a three-line variant; went with plain text for consistency with the rest of the
panel and to avoid a new image-loading path for a cosmetic-only difference.

**Follow-up:** the current-turn indicator moved off the name text (which used to turn orange) onto
a seat-colored outline around the whole entry -- one less thing competing for the name's own
color, and it doubles as a reminder of which seat color belongs to which player. The outline's
own border thickness is subtracted from the row's normal padding so a turn starting/ending doesn't
shift the row's height or the ones below it.

## AnnouncementOverlay text now fits any screen size

**Status:** done.

**The problem:** every banner in `AnnouncementOverlay` (turn announcements, mini-game titles, the
ready-check/standings/rewards lists, the whole ceremony) was authored assuming a normal-sized game
window. On a smaller or resized RuneLite client, long lines (a wordy stats string, a long RSN plus
suffix, the ready-check instructions) could run past the canvas edge and get clipped.

**The fix:** `client.getCanvasWidth()` now drives a `safeTextWidth()` computed fresh every render
call (no resize listener needed — the next frame just sees the new size). Two mechanisms sit on top
of it:
- `drawCenteredText`/`drawCenteredRainbowText` — the two primitives ~90% of this file's text draws
  already funnel through — now auto-shrink the font (`fitFont`/`fitScale`) when a line would
  overflow, then restore the caller's original font before returning. Every existing call site
  (titles, names, numbers) got this for free, no changes needed.
- Composite lines built from several independently pre-measured segments (the Spin hint, Jad/Yes-No
  emote instructions) and row lists (`drawPlayerRows`, the True/False reveal) can't shrink
  themselves that way — their callers already laid out x-offsets from a fixed design size before
  drawing. Those now measure their own worst case up front, get one shared scale, and re-derive
  every segment/row's font by that same amount, so a standings list's name column stays aligned
  instead of each row drifting to its own size.

Genuine multi-sentence content (mini-game instructions, True or False questions) still *wraps*
rather than shrinks (`drawWrappedCenteredText`) — a paragraph squeezed onto one line goes unreadable
long before it'd fit — but its design max-width is now capped by `safeTextWidth()` too, not just a
fixed 620px constant.

Not covered: a handful of multi-line text *blocks* that call `drawCenteredText` once per line
(e.g. the Chance Tile announcement) — each line is individually guaranteed to fit on screen, but
isn't scaled in lockstep with its neighbors the way a row list is. Deliberate scope call: the
primary ask (nothing gets clipped) is met; perfect line-to-line visual consistency in every block
wasn't worth chasing across every remaining call site.

## Golden Gnome ceremony sequencing rebuilt as a single ordered queue

**Status:** fixed properly, superseding an earlier local patch that turned out to be incomplete.

**The bug report (round 1):** the Gnome's opening line ("Congratulations, Players!...") appeared
while the final mini-game's own score/rewards/"Current Standings" recap was still on screen, even
though the ceremony arena and Golden Gnome models correctly waited until after the recap finished.

**The bug report (round 2, after the first patch landed):** the whole ceremony was "out of order,"
with several sequences overlapping each other — worse than before in places.

**What the first patch actually did, and why it wasn't enough:** `CeremonyPresentation` used to mix
three different scheduling idioms — a `gameOverTask`/`scheduleAfterTurnEffects` chain for the title
and the whole winner-reveal half, a bespoke `nextGnomeLineRevealAt` timestamp bolted on to fix round
1's bug for just the Gnome's own lines, and *no gating at all* for the bonus-round announcements and
winner reveals. That third group was the real problem, and the first patch never touched it: once
the recap delay pushed the Gnome's own lines later than `ceremony.py`'s fixed sleep assumed, every
later beat — being applied immediately and unconditionally the instant its own server event landed —
started rendering on top of whatever earlier, now-late beat was still on screen. Fixing the Gnome's
lines in isolation didn't fix the sequence; it just moved the point where the un-gated rest of the
ceremony collided with it. (Also found along the way: there's a real, unpredictable
player-driven arrival-wait between the title and the first Gnome line too, which is why the first
bug wasn't 100% reproducible — a fast arrival can mask the race, a slow recap can't.)

**The actual fix:** every ceremony beat — title, gather-message reveal, both Gnome lines, both
bonus-round announcements and their suspense/reveal/vanish/popup sub-beats, the transition, and
the entire standings/winner-reveal sequence through "GAME OVER!" — is now enqueued onto one single
ordered sequence (`CeremonyPresentation#enqueue`) instead of three different ad hoc mechanisms.
Every call computes its own reveal time as strictly after both the shared turn-effect gate (only
ever relevant to the very first beat) and this class's own running "next available slot," which only
ever moves forward. A beat can never be skipped (a queue, not a cancel-and-replace scheduler) and
two beats can never show at once (nothing is ever revealed except in strict turn order), by
construction — this removes the entire class of bug instead of patching each symptom as it's
found. Every `handleX` method is now a flat, linear list of `enqueue(duration, revealSomething)`
calls in the exact order the ceremony plays, so the code reads as the sequence itself. A
`ceremonyGeneration` counter invalidates any still-pending beat if the ceremony is reset
mid-sequence (a force-ended game), replacing the old single-`ScheduledFuture`-handle cancellation
that could only ever guard one pending task at a time.

**Design lineage:** this is the "Event Queue" pattern (Robert Nystrom, *Game Programming Patterns*)
applied deliberately, after review turned up that the codebase's one general-purpose scheduling
primitive (`scheduleAfterTurnEffects`) only implements *replace* semantics, correct for a single
producer chaining its own steps but wrong for independent, externally-triggered producers where
dropping or overlapping content is a real bug — the exact distinction that pattern treats as two
different, deliberate design choices rather than one mechanism doing both.

**Not touched:** `MinigamePresentation`'s own generic ready-check/countdown sequencing and the
`scheduleAfterTurnEffects` primitive itself, both left as-is — this redesign is scoped to
`CeremonyPresentation`, the one place that actually needed queue semantics across its whole
sequence. No server change was needed.

## Arrival-gated mini-games starting before their own "BEGIN!" flash

**Status:** root cause found and fixed for Repeat After Me; the general mechanism this exposed a
gap in was already in place for everything else.

**The bug report:** Repeat After Me's sneak peek visibly lit up before the "BEGIN!" banner had
actually appeared.

**Why there are two different "BEGIN!" banners in the first place (not a bug, a real distinction):**
the generic "3...2...1...BEGIN!" countdown is for mini-games with a fixed, known wait (ready-check
YES-emotes, then a flat countdown) — a real numeric countdown only makes sense when the wait length
is actually known in advance. Arrival-gated mini-games (`RunePartyPlugin#ARRIVAL_GATHER_KEYS`:
Arena, Turf Wars, Sandwich Rush, Jaddy, Hot Potato, Dance Dance RuneScape, Repeat After Me, Rune
Match, Balloon Pop) instead wait on a variable, walk-dependent "has everyone arrived yet" gate —
there's no fixed number of seconds to count down, so they show a "gather here" message for however
long that takes and then flash a plain "BEGIN!" (no numbers) the instant everyone's in position.
Collapsing these into one wouldn't work: you can't show "3...2...1" against a wait of unknown
length without either lying about the countdown or reinventing the exact same "wait, then flash"
shape under a different name.

**The gating mechanism the user asked for already exists** — `MinigamePresentation`'s
`MINIGAME_ROUND_BEGIN` handling defers calling a mini-game's own `onRoundBegin()` hook until the
arrival "BEGIN!" flash's own `armBanner`/turnEffectGate reservation has actually resolved, not the
instant the event lands (this is exactly what was built to fix the identical bug for Dance Dance
RuneScape previously — see that class's own `onRoundBegin`, and Rune Match's, both already anchor
their reveal off this hook correctly).

**Why Repeat After Me slipped through anyway:** unlike DDR (which picks its own sequence
client-side, entirely inside `onRoundBegin()`) and Rune Match (whose own `apply()` only stores a
seed, revealing nothing), Repeat After Me *has* to receive its target cells from the server (every
player must solve the same puzzle) via its own dedicated `REPEAT_AFTER_ME_ROUND_STARTED` event —
and that event was applied immediately and unconditionally in `apply()`, with no deferral of its
own, completely bypassing the `onRoundBegin()` gate. `MINIGAME_ROUND_BEGIN` (deferred) and round
1's `REPEAT_AFTER_ME_ROUND_STARTED` (not deferred) arrive back-to-back from the server, so whichever
one the client's own turn-effect-gate happens to take longer to visually resolve can lose the race.

**Fix:** `RepeatAfterMePresentation` now buffers round 1's payload (`pendingFirstRoundNumber`/
`pendingFirstRoundIndices`) in `apply()` instead of committing it immediately, and only calls the
same reveal logic (`applyRoundStarted`) from `onRoundBegin()` — the hook that's already correctly
deferred behind the flash. Rounds 2/3 still apply immediately, since nothing cosmetic gates the
`INTERMISSION_SECONDS` gap between them, so there was never a race there. A catching-up client
(which already missed the one-shot "BEGIN!" reveal) also applies round 1 immediately, matching
every other real-state fold in this codebase.

**Not exhaustively re-audited:** Arena, Turf Wars, Sandwich Rush, Jaddy, Hot Potato, and Balloon Pop
are also in `ARRIVAL_GATHER_KEYS` but weren't individually re-checked for the same shape of bug
this pass — none of them appeared to have an equivalent "own dedicated round-started event with a
reveal payload" the way Repeat After Me does, but that's an inference from their general shape, not
a line-by-line confirmation.

## POH tile compatibility

**Status:** client-side render fix shipped.

**Motivation:** the sibling Gnomeball project shipped the same fix (`3ebb448` "POH Compatibility")
and this codebase had the identical bug. RuneLite's instance model has two directions:
local→world (what `Tile#getWorldLocation()`/`Player#getWorldLocation()` use, and what
`WorldPoint.fromLocalInstance` implements) always resolves to exactly one answer, so it's safe
even inside a POH — RuneLite just returns *some* stable coordinate per physical tile, even though a
POH's own "template" reference isn't a real overworld place. World→local
(`WorldPoint.toLocalInstance`, used only when *rendering* a previously-captured point back onto
the screen) is where it breaks: a POH is built from a small catalog of reusable room shapes, so
multiple physically-different rooms can share the same recorded template-chunk reference, and
`toLocalInstance` returns every room built from that shape instead of just the one tile that was
actually marked.

**What shipped:** every render-path call site that converted a stored `WorldPoint` via
`WorldPoint.toLocalInstance(...)` before projecting it to canvas now converts with
`LocalPoint.fromWorld(...)` directly instead — the point was already captured via
`getWorldLocation()`, so it's already instance-safe and needs no template re-expansion. Fixed in
`TileOverlay.java` (`renderBrutusAttackCrashZone`, `renderOutlinedTile`, `renderTurfWarsTile`,
`fillArenaCell`, `renderRainbowRushTile`) and `DanceDanceRuneScapeOverlay#canvasTilePoly`. Outside
any instance the two conversions are identical, so this doesn't change ordinary overworld
rendering at all.

**Verified as already correct, no change needed:** the tile-*marking* (capture) side —
`CourseBuilder.java` gets its `WorldPoint` from `Tile#getWorldLocation()` directly, which is the
local→world direction and was already POH-safe. No server change either; the server only ever
stores/broadcasts raw x/y/plane and has no concept of instances at all.

**Constraint worth naming (not a code issue):** every seated player still has to be physically
standing inside the *same* house instance (the host's own house, everyone else visiting as
guests) for a POH-hosted course to render consistently across clients — that's just how POHs work,
not something client code can route around.

## Rendering performance audit

**Status:** first pass shipped; one item deliberately deferred.

**Motivation:** a sibling project (Gnomeball) had a bug where text and tile rendering were being
recomputed from scratch on every single frame instead of once when the underlying data actually
changed. That prompted an audit of this codebase's own `Overlay#render()` paths — called on the
order of ~50 times/second, far more often than the game's own 600ms tick — for the same class of
waste: real work (tile-snapshot scans, word-wrap, sorting, geometry) redone every frame for output
that's only ever a function of state that changes much less often.

**What shipped:**
- `TileReducer` gained a `revision()` counter, bumped on every real tile mutation
  (`applyMark`/`applyUnmark`/`reset`) — the cheap "did anything actually change" signal most of the
  fixes below key off of, instead of unconditionally redoing work every frame.
- `TileOverlay`: the three tile-anchored 3D models that are pure functions of tile state (Arena
  Fire, Table, Pond) now only re-sync when `revision()` changes. 8 per-minigame outline scans (Turf
  Wars, Sandwich Rush, Hot Potato, Repeat After Me, Crab Rave, Rune Match, Balloon Pop, Fishing
  Contest) are now gated behind their own `isXActive()` check instead of scanning the whole board
  for zero matches during ordinary turn-based play — the overwhelming majority of a game's runtime.
  Crab Rave's "lit tile" colors are memoized by their own 2-second phase window instead of
  reshuffling 64 elements every frame.
- `RunePartyPlugin#getTurfWarsTileCounts()` memoized the same way, fixing `TurfWarsScoreOverlay`'s
  per-frame full-board re-tally (its own doc comment used to say "tallied fresh every frame" out
  loud).
- `ChatboxDialogueOverlay#wrap()` (shared by the Item Shop/Wise Old Man dialogues) and
  `AnnouncementOverlay#drawWrappedCenteredText` both memoize their line-wrapping by `(text,
  maxWidth)` instead of re-wrapping static banner/dialogue text every frame.
- `JaddyDuelModel#updateZoneGeometry()`, `CrabRaveNpcOverlay`, `GnomeNpcOverlay` each cache a
  one-time geometric fact (duel zone centers, arena bounding-box origin) instead of re-deriving it
  from a tile scan every frame.
- `RunePartyMapOverlay#buildLegendRows()` memoized on the tile-type catalog's own object identity
  (only ever reassigned once, when the real catalog fetch lands).

**Regression caught during play-testing, fixed:** `GoldenGnomeModel`/`CoinTrapModel` were initially
swept into the same revision-gated caching as the three genuinely tile-state-only models above, but
neither one's `update()` is a pure function of tile state — each has its own wall-clock choreography
(a relocation's hide-old/show-new window, a trigger's force-persist window) that has to keep being
re-evaluated every frame, independent of any tile mutation, purely because time is passing. Gating
them left an old Golden Gnome model permanently stuck on screen after a relocation, since nothing
ever called `update()` again to close that window. Fixed by excluding those two from the revision
gate entirely (back to real per-frame calls, their original behavior) while keeping the gate on the
three that are actually safe. A related gap (`renderFishingZoneOutline` calling unconditionally,
missed because it's a bespoke scan rather than a call through the shared `renderArenaOutline`
helper) was caught the same way and closed.

**Deliberately deferred:** `AnnouncementOverlay`'s 5 score/rewards/round-complete banners still
re-sort and re-hash the seated-player roster every frame. The real fix needs a revision counter on
`RosterReducer`, which mutates from far more event types than `TileReducer` (coins, items, roles,
...) — correctly instrumenting every mutation site felt like too much risk for an unsupervised
pass, and it was the lowest-severity finding of the whole audit anyway (≤8 entries, low
single-digit microseconds, not visibly janky on its own).

**Second pass: the base course itself, not just minigame overlays.** Everything above was scoped to
"unnecessary recompute" gated by minigame state. Prompted by "is the *default* course rendering
efficiently already?", a follow-up pass looked at code that's on the critical path for the whole
game, all the time — and found an actual algorithmic-complexity bug, not just redundant caching:
- `TileReducer#tileAtIndex()`'s own doc claimed it was "only ever called once per dice roll," which
  was stale — `TileOverlay#renderRouteLines()` calls it once per outgoing edge of every course tile,
  every one of the ~50 frames/sec `render()` runs, for as long as the ordinary course is showing
  (most of a game). Since `tileAtIndex` was a linear scan, that made route-line rendering O(n²) on
  the course's own tile count, unconditionally, the whole game. Fixed at the source: `tileAtIndex`
  now maintains its own `pathIndex -> TileEntry` map internally, rebuilt only when `revision()`
  moves. Every one of its ~20 call sites (`renderRouteLines`, `renderReturnToPositionArrow`,
  `renderTargetArrow`, `RunePartyMapOverlay`, `RainbowRushPresentation`, `CourseBuilder`, several
  spots in `RunePartyPlugin`, `TileReducer#stepsBetween`'s own BFS) got the O(1)-amortized fix for
  free, with zero call-site changes.
- `TileOverlay#resolveColor`/`#defaultColorFor` called `Color.decode(hex)` — a string parse plus a
  new `Color` allocation — for every tile, every frame, even for the common case of an uncolored
  tile falling back to its type's default. Memoized by hex string (a pure function of the hex alone,
  so safe to cache regardless of which tile/type asked for it); only the successful decode is
  cached, not the exception-fallback path.
