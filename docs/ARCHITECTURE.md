# Architecture

Rune Party Showdown (`gay.runescape.runeparty`) is a [RuneLite](https://runelite.net) plugin: a
Mario-Party-style board game played on top of live OSRS — dice rolls, a tile-based course,
timed mini-games, items, coins. This document describes how the plugin itself is put together.
It does not cover the companion server (a separate FastAPI/Python repository) beyond the shape of
the contract between the two.

## The client/server split

**This repository holds no game-authoritative logic at all.** Every dice roll, every minigame
winner, every coin total is decided server-side. The client's entire job is:

1. Report player actions (roll, confirm arrival, use an item, submit a minigame result) as HTTP
   requests.
2. Receive the server's own resulting events over a live WebSocket.
3. Fold those events into local state and render it.

Even where the client appears to "help" — Rainbow Rush's self-reported tile count, a dev-only
forced dice roll — the server always re-validates before anything is actually paid out or applied.
Trust the server, never the client's own guess about what should have happened.

Every meaningful change to the game arrives as one typed **event**: a `(type, payload)` pair,
defined by string constants in `net/Events.java` that must match the server's own `EventType`
enum by name. There is no shared schema between the two repositories — payload shape is
documented in prose on both sides, which is the single biggest place the two can silently drift
(a renamed field just degrades to `null`). When you add a new event type or payload field, treat
this as a real handshake: update both sides in the same change.

## Connect → events → state → render

```
RunePartyPlugin (Guice-managed RuneLite Plugin)
   |
   +-- SessionManager        create/join/start/end/leave a game, session persistence & reconnect
   |
   +-- ApiClient             one HTTP call per player action / read (REST)
   |
   +-- EventSocket           the live WebSocket -- reconnects with full-jitter backoff
   |      |
   |      v
   +-- EventListener (impl'd by RunePartyPlugin)   onEvent(EventOut, catchingUp) / onCaughtUp / onError
          |
          v
   handleEvent(...) dispatch, per event type:
      -> TileReducer.apply(...)        real board state
      -> RosterReducer.apply(...)      real roster/coin/item state
      -> MinigamePresentation.apply(...) -> per-minigame *Presentation.apply(...)   cosmetic state
      -> other *Presentation classes (Jad, Golden Gnome, Item, Chance Space, Ceremony, Wise Old Man, Item Shop)
      -> a handful of things handled inline in RunePartyPlugin itself
          |
          v
   ~20 Overlay subclasses (overlays/) read that state every render() and draw it
```

**`catchingUp` is the one flag that matters most when reading this codebase.** Every (re)connect
opens the WebSocket at `afterSeq = lastSeq`, so the first burst of events on that connection is
always a replay of history, not live play — `EventSocket` tracks the boundary via the server's own
`CAUGHT_UP` sentinel. Real, durable state (a tile's color, a player's coin total, whose turn it is)
folds identically either way — the game has to be correct for a client that just reconnected.
Cosmetic state (a banner, a spinner, a damage popup, a chat line) is explicitly gated on
`!catchingUp`, or a reconnecting client would get every missed animation firing at once. When you
add a new event handler, decide up front which bucket it's in.

## Reducers: the real state

- **`TileReducer`** — the entire board, as a map keyed by `(x, y, plane, tileType)`. Each
  `TileEntry` carries its own `pathIndex` (nullable — null for a decorative modifier like a Golden
  Gnome sitting on top of a real tile) and its own explicit `nextIndices` (outgoing graph edges).
  There is **no implicit "+1, continue to the next tile" edge** — every connection, including a
  plain loop continuation, is set explicitly when a course is built. This is what lets a
  host-edited course have forks, dead ends, or gaps without the client ever guessing wrong about
  where a roll lands.
- **`RosterReducer`** — one `RosterEntry` per seated/joined player or spectator: role, seat color
  number, coins, Golden Gnome count, held items, online/joined flags.

Both are plain "fold an event into a map" reducers with no cosmetic concerns at all — an overlay
never talks to the network, only to these (via `RunePartyPlugin`'s own facade getters) and to the
`*Presentation` classes below.

## Presentation classes: cosmetic-only state, extracted out of `RunePartyPlugin`

`RunePartyPlugin` is the oldest and largest class in the codebase (4,500+ lines) and used to hold
every feature's state directly. The established pattern now is: **the instant a feature's own
state is self-contained, pull it into its own class**, under `presentation/` for whole-game
concerns (`JadPresentation`, `GoldenGnomePresentation`, `ItemPresentation`, `ChanceSpacePresentation`,
`CeremonyPresentation`, `WiseOldManPresentation`, `ItemShopPresentation`, and the top-level
`MinigamePresentation`), or under `minigames/` for one specific minigame's own state
(`CoinRushPresentation`, `TurfWarsPresentation`, `RainbowRushPresentation`, ...). `RunePartyPlugin`
still exposes the exact same getters it always did — they just delegate now. This keeps the god
class's growth roughly linear instead of quadratic as features are added.

`MinigamePresentation` itself is the generic minigame lifecycle: selection spinner, ready-check,
countdown, round-begin/end banners, rewards recap — shared by every minigame regardless of which
one is active. A minigame with per-round state of its own implements
`minigames/MinigamePresentationFeature`:

```java
public interface MinigamePresentationFeature {
    default void apply(ApiClient.EventOut e, boolean catchingUp) {}
    default void onStarted(boolean catchingUp) {}
    default void onRoundBegin() {}
    default void onEnded() {}
    default boolean showsFinalScore() { return false; }
    void reset();
}
```

`MinigamePresentation` holds one instance per registered minigame key and dispatches every event
its own generic switch doesn't recognize to whichever one is currently active. A minigame with no
state of its own beyond the generic lifecycle (Arena) simply has no implementation at all — there's
nothing to add.

## Registries: `Minigame` / `Item` / tile types

`minigames/`, `items/`, and the server-served tile-type catalog all follow the same shape: a small
interface (`Minigame`, `Item`), a package of self-contained implementations, and a
`KeyedRegistry<T extends WheelEntry>` that each implementation registers itself into, keyed by the
same string key the server uses. `WheelEntry` is what lets `AnnouncementOverlay`'s selection-wheel
spinner draw a minigame and an item identically. Adding a new minigame or item never means finding
and editing a central dispatch — you drop a class in the package and add one registration line.

The tile-type catalog works the same way from the server's side: colors, display names, and two
booleans (`isModifier`, `isMinigameTile`) are served once via `GET /v1/tile-types` and read
generically everywhere a tile needs to be drawn or categorized (`TileOverlay`, the course-builder's
"Set Tile" menu, the Board Map legend, the side-panel tile legend). Nothing about a specific tile
type is ever hardcoded on the client beyond its key string.

## Overlays: the render layer

Every visual element is a `net.runelite.client.ui.overlay.Overlay` subclass in `overlays/`. Three
broad shapes show up repeatedly:

1. **Board overlays** — `TileOverlay` draws the whole course every frame straight from
   `TileReducer`'s live snapshot; `PlayerOverlay` draws every seated player's token/outline in
   their own seat color (or a minigame-assigned team color, when one applies).
2. **The screen-centered announcement overlay** — `AnnouncementOverlay` is the single largest
   overlay in the plugin: every cosmetic reveal (dice roll, item wheel, minigame selection,
   ready-check, countdowns, outcome banners) renders from here, driven by `TimedBanner`-backed
   state. See **Best practices** below for why gameplay moments belong here and not in the side
   panel.
3. **Decorative 3D model spawners** — `models/` (`GoldenGnomeModel`, `CoinTrapModel`,
   `ArenaFireModel`, ...) plus a few that live directly in `overlays/` because they're
   overlay-shaped themselves (`JaddyDuelModel`, `ItemShopNpcOverlay`, `WiseOldManNpcOverlay`).
   These spawn `RuneLiteObject`s with a real 3D model, purely for visual flavor — zero gameplay
   effect.

`ChatboxDialogueOverlay` is a shared base class for the "drawn chatbox dialogue" look (chathead,
speaker name, greeting text, clickable option rows) used by `ItemShopDialogueOverlay` and
`WiseOldManDialogueOverlay` — the game's real dialog interface is never used; this is drawn
entirely by hand so it can be interactive without the friction of driving RuneScape's own scripted
dialog widgets.

## Shared primitives

A handful of small, generic classes exist purely because the same logic was independently
duplicated at multiple call sites first — reach for these before writing a new one-off version:

- **`SceneObjectSet<K>`** — given a "desired" set of keys this frame, spawns/despawns
  `RuneLiteObject`s to match, lazily (re-)loading each one's `Model` via a supplier that's retried
  every frame until it succeeds. This is what every decorative model spawner above is built on.
- **`TimedBanner<T>`** — `start`/`until`/`payload`/`task`. Every one-shot cosmetic reveal is armed
  via `RunePartyPlugin#armBanner` and read back by an overlay's `render()` as "is `until` still in
  the future." `AnnouncementOverlay` also chains several of these behind each other (via
  `scheduleAfterTurnEffects`/`turnEffectGateUntil`) so, say, a dice-roll reveal and a minigame
  banner never draw on top of each other.
- **`KeyedRegistry<T extends WheelEntry>`** — described above.
- **`RunePartyRender`** — small rendering/model helpers shared across `overlays/`: merging an
  NPC's model parts, loading a raw pre-lit `ModelData` for hand-recoloring, the angle math for
  "face this direction," and the shadow-then-draw text idiom.

## Courses

- **`CoursePreset`** — a course is an ordered list of `RelativeTile` (`dx, dy, tileType, color,
  nextIndices`) relative to a center anchor; list position becomes `pathIndex` 1:1 on commit.
  Rotation is a single shared transform used identically by the live placement preview and the
  real commit, so the two can never disagree about geometry.
- **`CourseBuilder`** — the host-facing in-world building tools (place a preset, freehand
  placement, "Connect From"/"Connect To" for wiring `nextIndices` by hand, remove a tile). A
  removed tile's own slot is deliberately never reclaimed/renumbered — the gap is left for the
  host to notice rather than silently rewriting neighboring tiles' own edges.
- **`HardcodedCourse`** — the built-in "Standard Loop" fallback course plus the registered set of
  real Standard Courses (e.g. Fally Park).

## Tests / build

There is no automated test suite in this repository — verification is manual (run the dev client,
force a minigame via the companion server's own dev routes, watch it play out) or via the
companion server's own `pytest` suite, which covers the game-authoritative logic this repo never
duplicates. `./gradlew compileJava` is the fast, cheap correctness check for this repo; run it
after any change.

---

## Best practices

Conventions this codebase already leans on hard, worth following rather than reinventing:

1. **Doc comments explain *why*, never *what*.** An identifier already says what a method does;
   the comment is for the non-obvious part — a subtlety a future reader would otherwise
   re-discover the hard way, a past bug this shape specifically fixes, or a constraint from the
   game/engine that isn't visible in the code itself. If removing the comment wouldn't confuse
   anyone, it shouldn't be there.

2. **Real state and cosmetic state are always applied differently on catch-up.** Real state
   (positions, coins, roster, tile ownership) folds unconditionally — it has to be correct for a
   client that just reconnected mid-round. Cosmetic state (banners, spinners, popups, chat lines)
   is gated on `!catchingUp`, or a reconnecting client gets every missed animation blasted at once.
   Decide which bucket a new event handler is in before writing it.

3. **The server is the only authority — always.** The client never computes a dice roll, a winner,
   or a reward. It requests, waits for the server's own event, and renders that. This holds even
   for client-reported numbers (Rainbow Rush's tile count, a dev-forced roll) — the server
   re-validates before anything is actually paid out.

4. **Prefer a self-registering entry over a growing switch statement.** Minigames, items, and tile
   types are each "a small class implementing an interface, registered into a `KeyedRegistry`" —
   never a big `if`/`else` keyed on a string. Adding one never means finding and editing a central
   dispatch point.

5. **Rebuild expensive UI only when the thing driving it actually changes**, not on every refresh
   tick. `RunePartyPanel`'s `minigameControlSlot`/`itemUsePanel`/`legendCard`, and similar spots
   elsewhere, each track a "last built for `X`" sentinel and no-op otherwise — these are Swing
   component rebuilds, potentially disruptive to whatever the player's mid-interaction with, firing
   from a `refresh()` that can run many times a second.

6. **Extract a feature's own state into its own class the moment it's self-contained.** The
   `presentation/` package and every per-minigame `*Presentation` class exist because
   `RunePartyPlugin` used to hold all of this directly. `RunePartyPlugin` still exposes the same
   getters under the same names, just delegating — callers never need to know a feature moved.

7. **One shared mechanism beats N independent copies.** `SceneObjectSet`, `TimedBanner`,
   `KeyedRegistry`, `RunePartyRender` all exist because the same 20–30 lines were independently
   duplicated at 3+ call sites first. If you're about to write a spawn/despawn loop, a fade-timer
   field, or a "look this up by key" map by hand, check whether one of these already does it.

8. **A tile/item/minigame *type* is data the server owns — never hardcode it a second time.**
   Colors, display names, `isModifier`/`isMinigameTile` are served once via the catalog and read
   generically. A new type should need zero client code to show up correctly in the legend, the
   course-builder menu, or the Board Map.

9. **Recoloring a hand-merged model needs the recolor pairs reapplied explicitly.** The real
   game's own object-spawn pipeline applies an NPC/object's declared recolor slots automatically; a
   raw `Client#loadModelData`/NPC-composition merge does not. Every deliberately-recolored
   decoration (`ArenaFireModel`, `CoinTrapModel`, `JaddyDuelModel`, `ItemShopNpcOverlay`,
   `WiseOldManNpcOverlay`) reapplies its own `recolor(find, replace)` pairs by hand against the raw
   `ModelData`, once, and caches the result — never against an already-lit `Model`.

10. **Gameplay moments belong in `AnnouncementOverlay`, not the side panel.** The side panel
    (`RunePartyPanel`) is for persistent status and controls — the roster, host tools, item-use
    buttons, the tile legend — things a player checks, not things that need their attention *right
    now*. A reveal, a choice, a countdown, an encounter belongs screen-centered, where it can't be
    missed because the panel wasn't in view.

11. **Never assume a course is well-formed.** `pathIndex` values can have gaps — a removed tile's
    slot is deliberately never reclaimed, so neighboring tiles' own `nextIndices` are never
    silently rewritten. Code that needs "how many real tiles exist" must actually count them
    (`TileReducer#realTileCount`), not assume `max(pathIndex) + 1` is the true count — that
    assumption alone made an earlier Rainbow Rush win condition mathematically impossible to reach
    on any course with a gap in it.

12. **A one-shot client report needs a submission latch — and its failure behavior is a real
    decision, not an oversight.** Every fire-once report to the server (a minigame result, a
    purchase, an item use) guards against double-firing with a boolean or key-set latch. Matching
    this codebase's own established shape, that latch generally stays set even if the request
    fails (no automatic retry) — a deliberate, accepted tradeoff for a low-stakes cosmetic report,
    not something to silently change without noticing you've changed it.

## Where to look next

| Task | Start here |
|---|---|
| Add a new event type | `net/Events.java`, then wherever it's folded (a reducer or a `*Presentation`) — update the server's own `EventType` in the same change |
| Add a new minigame | `minigames/` (a `Minigame` + optionally a `MinigamePresentationFeature`), register in `Minigames.java`, wire into `MinigamePresentation`'s constructor, add a row to `docs/MINIGAMES.md` |
| Add a new item | `items/` (an `Item`), register in `Items.java`, add a row to `docs/ITEMS.md` |
| Add a new overlay | extend `net.runelite.client.ui.overlay.Overlay`, register/remove it in `RunePartyPlugin#startUp`/`shutDown` |
| Add a new tile type | server-side `tiles/` registry — the client picks it up automatically via the served catalog |
| Understand the companion server | a separate repository; this client only ever assumes the REST/WebSocket contract described in `net/ApiClient.java` and `net/Events.java` |
