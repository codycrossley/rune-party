# Mini-games

Every mini-game the server can pick for a round, in the order it's registered in
`minigames/__init__.py` (server) / `minigames/Minigames.java` (client). Descriptions here are a
brief paraphrase for quick reference, not the real instructions text — the authoritative wording a
player actually sees comes from each mini-game's own `instructions` (server-side, in
`minigames/<key>.py`). Rewards are likewise a paraphrase of each mini-game's own payout call
(`pay_out`/`pay_out_flat`/`pay_out_top`/`pay_out_ranked`/`pay_out_flat_with_top_bonus`, all on
`MinigameContext` in `app.py`) and its own `FLAT_REWARD`/`RANKED_REWARDS`/etc. constant(s) — the
authoritative numbers live in that same `minigames/<key>.py`. `ArenaTestMinigame` (`arena-test`) is a dev-only mini-game kept out of the server's `REGISTRY` on
purpose (see that file's own `_DEV_ONLY` doc) so it can never be randomly picked into a real game --
not listed here for the same reason it isn't in `REGISTRY` itself.

**Keep this table current: adding a new mini-game to `minigames/__init__.py`/`Minigames.java`
means adding a row here in the same change — and changing a `FLAT_REWARD`/`RANKED_REWARDS`/etc.
constant means updating that mini-game's own Rewards cell in the same change too.**

| Name                   | Key | Description | Rewards |
|------------------------|---|---|---|
| Flame Field            | `arena` | Get onto the 4x4 arena — tiles heat up and turn red; get caught on one or step off and you're out. Survivors earn coins. | 35 coins to every survivor |
| Hot Click Balloon      | `balloon-pop` | Gather in the arena — a balloon floats above your head. Click your own tile as fast as you can to inflate it; first to 100 clicks pops it and wins outright. | 35 coins, winner-take-all |
| Coin Rush              | `coin-rush` | Coins spawn on random tiles across the board; race to grab them for +7 coins each. | 7 coins per coin collected |
| Brutus Bullet          | `brutus-attack` | One random player transforms into Brutus and dashes across the arena over several rounds, trying to crash into everyone else before they reach the far zone. | 35 coins to Brutus if he eliminates every target in time, otherwise 35 coins to every original target (even ones eliminated along the way) |
| Click! Click! Click!   | `click-click-click` | Right-click "Click!" on as many unique tiles as you can before time runs out. | 35 coins, most unique tiles clicked (ties share) |
| Crab Rave              | `crab-rave` | Dance on the dance floor — anyone who dances earns coins, whoever dances the most earns a bonus. | 35 coins to every dancer, +18 bonus for the top dancer(s) |
| Dance, Dance, RuneScape | `dance-dance-runescape` | Gather at the center tile, then step onto the surrounding tiles as they light up to score points. | 35 coins, top score (ties share) |
| Fishing Contest        | `fishing-contest` | Headbang next to the fish bowl to catch shrimp and anchovies; most anchovies wins. | 35 coins, most anchovies (ties share) |
| Hot Potato             | `hot-potato` | A potato appears above one player's head — SPIN to pass it before it explodes on whoever's holding it. | 53 coins to everyone except whoever's left holding it |
| Rainbow Rush           | `rainbow-rush` | A race along the live main course itself — every tile you step on turns into a rainbow; first to visit every tile wins. | Ranked: 35/18/11 coins for 1st/2nd/3rd |
| Repeat After Me        | `repeat-after-me` | Memorize which tiles light up, then stand on each one and SPIN before time runs out — 3 rounds, each harder than the last. | 35 coins, most rounds completed (ties share) |
| Rune Match             | `rune-match` | Gather in the arena, then SPIN on tiles to flip them — find all 8 matching rune pairs before anyone else. Everyone's board looks the same, but your own progress is private. | Ranked: 35/18/11 coins for 1st/2nd/3rd |
| Sandwich Rush          | `sandwich-rush` | Grab a tomato, cheese, cabbage, and bread floating around the arena to assemble sandwiches; most sandwiches wins. | 35 coins, most sandwiches (ties share) |
| True or False          | `true-or-false` | 5 True/False questions — YES/NO emote to answer, coins per correct answer plus a bonus for a perfect run. | 4 coins per correct answer, +15 bonus for a perfect (5/5) run |
| Turf Wars              | `turf-wars` | Split into two teams (or free-for-all with an odd count) and claim arena tiles by standing on them; most tiles wins. | 35 coins, most tiles (ties share) |
| Who's Your Jaddy?      | `whos-your-jaddy` | Two Jads duel — pick a side and stand in its zone; everyone standing with the surviving Jad wins coins. | 35 coins to everyone standing in the winning zone |

## Adding a new mini-game

See `ARCHITECTURE.md`'s "Where to look next" table for the client-side registration steps
(`minigames/`, `Minigames.java`, `MinigamePresentation`). On the server, add the implementation to
`minigames/__init__.py`'s `REGISTRY`. Either way, add a row above in the same change.
