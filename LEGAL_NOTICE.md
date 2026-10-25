# Seed Atlas for Xaero's World Map legal notice

Seed Atlas for Xaero's World Map is free software under the GNU General Public
License version 3. See `LICENSE` for the complete license text.

The generation engine and map icons come from DUzzL's Seed Atlas project,
which is derived from Cubitect's Cubiomes Viewer. This mod's Minecraft 26.3
update uses Seed Atlas commit fc2c628693bc72130d7c16a21636f50d57553ddf.
Original copyright notices remain in effect. The 26.3 integration changes
were made on 16 September 2026 and are distributed under GPLv3 without warranty.

This is an independent extension, not an official Xaero release. It is not
an official Minecraft product and is not approved by Mojang or Microsoft.

## This fork

Maintained by LOU20202020 (https://github.com/LOU20202020) on top of the Seed Atlas
26.3 integration. Changes in 2.0.0 (October 2026), all under GPL-3.0:

- structure inspector: pieces, chests and predicted loot (`native/sax_detail.c` over the
  xpple/cubiomes loot tables), callable from the Xaero map's right-click menu
- Chinese localisation: item names from the game's own registry, plus enchantment, loot
  table and biome tables
- legacy 1.17 biome ids filtered against the running game's biome registry
- left-column quick labels drawn without button chrome, auto-placed below Xaero's buttons
- Xaero's World Map requirement relaxed from 1.47.1 to 1.47.0

No warranty, as with upstream.
