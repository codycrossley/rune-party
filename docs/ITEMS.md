# Items

Every item a player can be granted from an Item Space or buy from the Item Shop, in the order it's
registered in `items/__init__.py` (server) / `Items.java` (client). Descriptions here are a brief
paraphrase for quick reference — the authoritative player-facing wording is each item's own
`getEffectDescription` (client-side, in `items/<Item>.java`).

**Keep this table current: adding a new item to `items/__init__.py`/`Items.java` means adding a
row here in the same change.**

| Name | Key | Usage | Description |
|---|---|---|---|
| Energy Potion | `energy-potion` | Instant (self) | Adds a bonus to your very next dice roll. |
| Coin Trap | `coin-trap` | Placed on a tile | Place it on a tile adjacent to you — steals coins from anyone else who lands on it. |
| Ice Barrage | `ice-barrage` | Targeted (player) | Use on another player to make them skip their next turn. |
| Tele Other | `tele-other` | Targeted (player) | Use on another player to send them 5 tiles backward along the course. |
| Home Teleport | `tele-home` | Instant (self) | Teleports you to the Start tile — walk over it again to collect the reward. |
| Gnome Glider | `gnome-glider` | Instant (self) | Instantly flies you to the tile immediately before wherever the Golden Gnome currently sits. |

## Adding a new item

See `ARCHITECTURE.md`'s "Where to look next" table for the client-side registration steps
(`items/`, `Items.java`). On the server, add the implementation to `items/__init__.py`'s
`REGISTRY`, and to `ITEM_SHOP_CATALOG` in `app.py` if it should also be purchasable from the Item
Shop. Either way, add a row above in the same change.
