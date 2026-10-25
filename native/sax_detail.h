/*
 * sax_detail.h - structure detail ABI (pieces, containers, predicted loot)
 * built on xpple/cubiomes (finders + features + loot).
 *
 * All strings are interned into a per-context pool and referenced by byte
 * offset, so the Java side can read them with a single memory segment.
 */
#ifndef SAX_DETAIL_H
#define SAX_DETAIL_H

#include <stdint.h>

#if defined(_WIN32)
#  if defined(SAX_DETAIL_BUILD)
#    define SAX_DETAIL_API __declspec(dllexport)
#  else
#    define SAX_DETAIL_API __declspec(dllimport)
#  endif
#else
#  define SAX_DETAIL_API __attribute__((visibility("default")))
#endif

#ifdef __cplusplus
extern "C" {
#endif

#define SAX_MAX_CHESTS 4

typedef struct {
    int32_t posX, posY, posZ;
    int32_t bb0x, bb0y, bb0z;
    int32_t bb1x, bb1y, bb1z;
    int32_t rot;
    int32_t type;
    int32_t chestCount;
    int32_t chestPos[SAX_MAX_CHESTS][3];
    uint64_t lootSeed[SAX_MAX_CHESTS];
    int32_t tableOff[SAX_MAX_CHESTS]; /* string pool offsets, 0 = none */
    int32_t nameOff;
} SaxPiece;

typedef struct {
    int32_t count;
    int32_t itemOff;      /* string pool offset of the item id */
    int32_t enchCount;
    int32_t enchId[6];
    int32_t enchLevel[6];
} SaxLootItem;

/* Initialise generation context. Returns 0 on success. */
SAX_DETAIL_API int sax_detail_init(uint64_t seed, int mcVersion, int dimension);

/* Recycle the string pool (call once before a batch of queries). */
SAX_DETAIL_API void sax_detail_reset_pool(void);

/* Number of structure types the engine knows. */
SAX_DETAIL_API int sax_detail_structure_count(void);

/* Structural piece list for a structure instance. stype uses cubiomes'
 * StructureType enum. Returns piece count (>=0) or a negative error. */
SAX_DETAIL_API int sax_detail_pieces(int stype, int regX, int regZ,
                                     SaxPiece *out, int outCapacity);

/* Same, but for a stronghold located via the ring algorithm. */
SAX_DETAIL_API int sax_detail_stronghold(SaxPiece *out, int outCapacity);

/* Resolve a string pool offset into a C string (valid until reset). */
SAX_DETAIL_API const char *sax_detail_string(int32_t off);

/* Roll a loot table with the given seed. Writes up to maxItems entries and
 * returns the item count, or a negative error. */
SAX_DETAIL_API int sax_detail_loot(const char *table, uint64_t lootSeed, int mcVersion,
                                   SaxLootItem *out, int maxItems);

/* Enchantment id -> readable name (static string). */
SAX_DETAIL_API const char *sax_detail_enchant_name(int32_t id);


/* Pieces for the structure near a block position (region resolved internally).
 * Strongholds use the ring algorithm. */
SAX_DETAIL_API int sax_detail_structure_at(int stype, int blockX, int blockZ,
                                           SaxPiece *out, int outCapacity);

#ifdef __cplusplus
}
#endif

#endif /* SAX_DETAIL_H */
