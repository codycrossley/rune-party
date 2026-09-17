# Changelog

Major changes to the Rune Party Showdown RuneLite plugin, newest first. This tracks this
repository (the client) — the companion server has its own history in its own repository.
Routine "point the client at the production server endpoint" commits are omitted below except
where noted; everything else of substance is listed.

Not a strict semantic-version log — this project ships continuously rather than in numbered
releases, so entries are grouped by date instead.

## 2026-09-17 — Post-launch testing feedback

- Tele Other item.
- "BEGIN!" banners polished across multiple minigames.
- Rainbow Rush scoring updates.
- Dance, Dance, RuneScape boundary fixes.
- Voidwaker death spot-animation for Flame Field (Arena).
- Coin Rush overlay updates.
- Item Shop logic updates and announcements.

## 2026-09-16 — Item Shop, dialogue system, legends

- **Item Shop tile** added, plus a from-scratch chatbox dialogue system
  (`ChatboxDialogueOverlay`) shared by the Item Shop and (later) the Wise Old Man encounters —
  chathead, speaker name, greeting text, and clickable option rows, drawn entirely by hand rather
  than through the game's own scripted dialog widgets.
- Native dialogue font implemented (`RunePartyFonts`/`GameFont`) instead of RuneLite's stock fonts,
  for a more authentic chatbox look.
- Item bugfix; Standard Loop course updated.
- Minigame UX improvements; Board Map and its legend improved — the map's own legend and (later)
  a new side-panel tile legend both switched to filtering by the server's `isModifier`/
  `isMinigameTile` flags instead of one-off hardcoded exclusions (e.g. a bespoke `DDR_CENTER_TILE`
  carve-out that quietly never got extended to any later minigame's own tile type).

## 2026-09-15 — Brutus Attack, Wise Old Man

- **Brutus Attack minigame**: one random seated player transforms into Brutus (a real NPC model
  rendered on a real Player) and dashes across a 12x3 arena, colliding with anyone who hasn't
  reached the far zone in time.
- **Wise Old Man tile**: a steal-coins/steal-a-Golden-Gnome encounter NPC, its own presentation
  class, dialogue, and NPC-model spawn overlay.

## 2026-09-13 — Crab Rave

- **Crab Rave minigame**: purely cosmetic NPC arena decoration with a flat-participation-plus-top-
  bonus reward shape — no arrival gate, standard countdown, entirely client-local dance-emote
  counting.
- Follow-up polish pass the same week.

## 2026-09-11 — Repeat After Me

- **Repeat After Me minigame**: a shared memory/reflex game across 3 rounds of increasing
  difficulty. Unlike Dance, Dance, RuneScape's own privately-picked-per-client sequence, the
  server itself picks which grid cells light up each round — judging success stays entirely
  client-local.

## 2026-09-10 — Gnome Glider item

- New **Gnome Glider** item; Coin Trap icon updated.

## 2026-09-08 — Dance, Dance, RuneScape; Rainbow Rush

- **Dance, Dance, RuneScape minigame**: gather on a board-swapped dance floor, then step onto
  whichever of four adjacent tiles lights up next — each client independently, deterministically
  picks the same fixed sequence (no server round-trip needed for the highlight timing itself).
- **Rainbow Rush minigame**: a race across the *live main course itself* rather than a swapped-in
  arena — every course tile temporarily recolors (later changed to a six-stripe Pride-flag fill
  per visited tile), and the first player to personally visit every tile wins. Notable because it
  doesn't fit the existing `SubmissionMinigame` shape (it resolves the instant *any* player
  finishes, not once everyone has) — this is the first minigame built as a plain timer-driven
  `Minigame` that still rides the server's generic `submit-minigame-result` endpoint, which
  required a real fix to that endpoint's own resolution guard so it never tries to wait on
  players who were never going to submit. Later revisions added a "traffic light" get-ready
  sequence, a rainbow arrow pointing at the Start tile during the gather phase, and a fix for
  courses with gaps in their own `pathIndex` sequence (`TileReducer#realTileCount`).

## 2026-09-09 — Minigame position optimizations

- Performance pass on the generic live position-reporting heartbeat every minigame shares.

## 2026-09-05–07 — Reorganization

- **Overlay restructure**, and a broader pass moving code into `net/`, `presentation/`, and
  `courses/` packages — the first real application of "extract a feature's own state into its own
  class" at package scale, rather than everything living directly on `RunePartyPlugin`.
- Entry point simplified; plugin images relocated.

## 2026-09-04 — Hot Potato

- **Hot Potato minigame**: a single item passed player-to-player entirely by choice (the SPIN
  emote) — no arena, no live scoring loop, just a holder that can change at any moment and a flat
  reward for everyone *not* holding it when the clock runs out.

## 2026-09-03 — Who's Your Jaddy?, Chance Tile, Click Click Click

- **Who's Your Jaddy? minigame**: two recolored TzTok-Jad NPCs duel it out while players pick a
  side to stand in — an arrival gate, a fixed run of attack beats with a live health bar/damage
  popup, and one random pre-decided resolution.
- **Chance Tile** added to the standard tile set.
- **Click Click Click minigame**: client-local-then-batch-submit scoring, no dedicated arena.
- Plugin description/title finalized for the Plugin Hub listing; promotional images resized.

## 2026-09-01–02 — Sandwich Rush

- **Sandwich Rush minigame**: a Turf-Wars-arena-shaped item-collection game — floating
  ingredients spawn/respawn continuously, same event-pair shape Coin Rush's own coins already use.
- Standard Course improvements (per-course offset tiles, so a board-swapped mini-arena always
  lands in the right spot for whichever real course is loaded).
- Item Space UI improvements; Arena renamed to "Flame Field" for its in-game display name.

## 2026-08-31 — Turf Wars, Fally Park

- **Turf Wars minigame**: a team-based board-swapping minigame — two teams for an even seated
  count, free-for-all (everyone their own team) for an odd one.
- Placeholder minigame removed now that real minigames exist to fill the wheel.
- **Fally Park**, a real built-in Standard Course, added; Board Map updated to match.
- Course-building UX adjustments.

## 2026-08-27–30 — Jad, items, custom courses

- **Initial Jad (Jad Tile) implementation**: the single-Jad "bow or be smashed" encounter on the
  main course, plus later timing fixes to its attack/bow-acknowledge animation windows.
- `models/` package introduced to organize decorative 3D model spawners out of their overlays.
- Golden Gnome flow improved (purchase/reachability/relocate handling).
- Start Tile changed to award +20 coins on landing.
- **Tele Block** and **Home Teleport** items added.
- **Initial custom course implementation** — host-driven freehand tile placement and connection,
  distinct from picking a preset.
- "Add to Game" roster UI improved; Fishing Contest minigame and the Arena's own Fire hazard
  effect added the same week.

## 2026-08-22–23 — `TimedBanner`, registry unification

- **`TimedBanner<T>` introduced**, replacing several hand-rolled `start`/`until`/`payload` field
  groups scattered across the plugin — the first shared primitive of its kind in the codebase.
- Banner animation logic generalized (`BannerAnim`); rendering helpers generalized
  (`RunePartyRender`).
- **Registries unified** — the shape that became `KeyedRegistry<T extends WheelEntry>`, shared by
  Minigames and Items instead of each maintaining its own lookup.
- Disconnect/WebSocket reconnect logic fixes; a placed-item bug fix; a round of comment cleanup
  and general refactoring.

## 2026-08-18–21 — First items, first real minigames

- **Item system implemented**: Energy Potion, then Coin Trap.
- **Coin Rush minigame**: the project's first real minigame — coin spawns appear on random tiles
  over a fixed round, first to reach one collects it.
- **True or False minigame**.
- Remove Player (host) endpoint added; a seat color/turn-order bug fixed.
- Panel and Plugin Hub icons added.

## 2026-08-07–13 — Foundation

- **Initial implementation**: the core turn-based board game loop — dice rolls, tile landing,
  standard tile types, the side panel, the in-world course overlay.
- **Initial Board Map implementation** (`RunePartyMapOverlay`).
- Early announcement/banner improvements.

---

*A note on dates: this project's commit history spans a compressed development timeline; the
dates above are as recorded by git and may not reflect real-world elapsed time between entries.*
