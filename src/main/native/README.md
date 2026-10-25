# Native prediction engine

The bundled Seed Atlas engine predicts vanilla biomes and structures for the Minecraft version targeted by this branch. Java accesses it through the primitive C ABI in `seedatlas_xaero.h`; incompatible native libraries are rejected during loading.

Predictions support Normal and Large Biomes worlds. Datapacks, custom dimensions, terrain changes and final structure placement can differ from the prediction. Approximate structures are identified in the map UI.

See [ENGINE_LICENSE](ENGINE_LICENSE) and the repository's [third-party notices](../../../THIRD_PARTY_NOTICES.md) for licensing and attribution.
