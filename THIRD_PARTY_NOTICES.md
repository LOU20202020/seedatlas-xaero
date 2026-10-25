# Third-party notices

## Seed Atlas and Cubiomes Viewer

The generation engine and the bundled Seed Atlas icons are sourced from
https://github.com/DUzzL/Seed-Atlas at commit
fc2c628693bc72130d7c16a21636f50d57553ddf. Seed Atlas derives from
https://github.com/Cubitect/cubiomes-viewer (GPLv3).

The Abandoned Camp markers use their own flat pixel-art tents: the normal camp
motif follows the approved Seed Atlas-style tent design, and the special-loot
tent is the same raster in blue. The swamp hut, igloo, shipwreck and desert well
icons retain their Seed Atlas designs with a 1 px outline. All other markers
show Minecraft block, item and mob textures (the villager and warden heads and
the chest are composed from entity textures), which are read at runtime from
the installed game or resource packs and are not distributed with this mod.

## cubiomes

Copyright (c) 2020 Cubitect. The engine derives from cubiomes, licensed under
the MIT License. Its complete license is included as `ENGINE_LICENSE` in the
mod jar and `src/main/native/ENGINE_LICENSE` in the source.

## Amidst

Biome colors and some icon concepts are inspired by Amidst (GPLv3):
https://github.com/toolbox4minecraft/amidst.

## Runtime dependencies

Minecraft, Fabric Loader, Fabric API, and Xaero's World Map are external
runtime dependencies and are not bundled in this mod jar. Their respective
licenses apply to their own distributions.

## SeedCrackerX (bundled)

SeedCrackerX, Copyright (c) KaptainWutax and 19MisterX98, is licensed under the
MIT License: https://github.com/19MisterX98/SeedCrackerX

The released jar bundles SeedCrackerX 2.16.2 under `META-INF/jars/` and declares
it in `fabric.mod.json`, so one file installs both. Its MIT licence ships inside
that nested jar as `LICENSE_seedcrackerX`.

`libs/seedcrackerx-api.jar` and `libs/seedcrackerx-2.16.2.jar` are the unmodified
upstream artifacts used at build time; only the public hand-off interface
(`kaptainwutax.seedcrackerX.api.SeedCrackerAPI`) is referenced by Seed Atlas
code, and the bridge class is loaded only when SeedCrackerX is present.
