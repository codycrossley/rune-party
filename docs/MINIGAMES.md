# Mini-games

Every mini-game the server can pick for a round, in the order it's registered in
`minigames/__init__.py` (server) / `minigames/Minigames.java` (client). Descriptions here are a
brief paraphrase for quick reference, not the real instructions text — the authoritative wording a
player actually sees comes from each mini-game's own `instructions` (server-side, in
`minigames/<key>.py`).

**Keep this table current: adding a new mini-game to `minigames/__init__.py`/`Minigames.java`
means adding a row here in the same change.**

| Name | Key | Description |
|---|---|---|
| Flame Field | `arena` | Get onto the 4x4 arena — tiles heat up and turn red; get caught on one or step off and you're out. Survivors earn coins. |
| Coin Rush | `coin-rush` | Coins spawn on random tiles across the board; race to grab them for +2 coins each. |
| Brutus Bullet | `brutus-attack` | One random player transforms into Brutus and dashes across the arena over several rounds, trying to crash into everyone else before they reach the far zone. |
| Click! Click! Click! | `click-click-click` | Right-click "Click!" on as many unique tiles as you can before time runs out. |
| Crab Rave | `crab-rave` | Dance on the dance floor — anyone who dances earns coins, whoever dances the most earns a bonus. |
| Dance, Dance, RuneScape | `dance-dance-runescape` | Gather at the center tile, then step onto the surrounding tiles as they light up to score points. |
| Fishing Contest | `fishing-contest` | Headbang next to the fish bowl to catch shrimp and anchovies; most anchovies wins. |
| Hot Potato | `hot-potato` | A potato appears above one player's head — SPIN to pass it before it explodes on whoever's holding it. |
| Rainbow Rush | `rainbow-rush` | A race along the live main course itself — every tile you step on turns into a rainbow; first to visit every tile wins. |
| Repeat After Me | `repeat-after-me` | Memorize which tiles light up, then stand on each one and SPIN before time runs out — 3 rounds, each harder than the last. |
| Sandwich Rush | `sandwich-rush` | Grab a tomato, cheese, cabbage, and bread floating around the arena to assemble sandwiches; most sandwiches wins. |
| True or False | `true-or-false` | 5 True/False questions — YES/NO emote to answer, coins per correct answer plus a bonus for a perfect run. |
| Turf Wars | `turf-wars` | Split into two teams (or free-for-all with an odd count) and claim arena tiles by standing on them; most tiles wins. |
| Who's Your Jaddy? | `whos-your-jaddy` | Two Jads duel — pick a side and stand in its zone; everyone standing with the surviving Jad wins coins. |

## Adding a new mini-game

See `ARCHITECTURE.md`'s "Where to look next" table for the client-side registration steps
(`minigames/`, `Minigames.java`, `MinigamePresentation`). On the server, add the implementation to
`minigames/__init__.py`'s `REGISTRY`. Either way, add a row above in the same change.
