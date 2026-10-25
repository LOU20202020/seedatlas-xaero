/*
 * sax_detail.c - structure pieces + predicted loot over xpple/cubiomes.
 */
#include <stdio.h>
#include <string.h>

#include "finders.h"
#include "generator.h"
#include "biomenoise.h"
#include "features/stronghold.h"
#include "loot/loot_tables.h"
#include "loot/loot_table_context.h"
#include "loot/loot_functions.h"

#define SAX_DETAIL_BUILD 1
#include "sax_detail.h"

#define POOL_SIZE (256 * 1024)
#define MAX_PIECES 1024

static char pool[POOL_SIZE];
static int poolUsed;

static Generator g;
static uint64_t curSeed;
static int curMc;
static int curDim;
static int ready;

static int intern(const char *s)
{
    if (!s)
        return 0;
    int len = (int)strlen(s) + 1;
    if (poolUsed + len >= POOL_SIZE)
        return 0;
    int off = poolUsed;
    memcpy(pool + off, s, len);
    poolUsed += len;
    return off;
}

void sax_detail_reset_pool(void)
{
    poolUsed = 1; /* keep offset 0 = empty string */
    pool[0] = '\0';
}

const char *sax_detail_string(int32_t off)
{
    if (off <= 0 || off >= poolUsed)
        return "";
    return pool + off;
}

const char *sax_detail_enchant_name(int32_t id)
{
    const char *n = get_enchantment_name((Enchantment)id);
    return n ? n : "?";
}

int sax_detail_init(uint64_t seed, int mcVersion, int dimension)
{
    if (dimension != DIM_OVERWORLD && dimension != DIM_NETHER && dimension != DIM_END)
        return -1;
    int mc = mcVersion < 0 ? MC_26_3 : mcVersion;
    if (ready && seed == curSeed && mc == curMc && dimension == curDim)
        return 0;
    setupGenerator(&g, mc, 0);
    applySeed(&g, dimension, seed);
    curSeed = seed;
    curMc = mc;
    curDim = dimension;
    ready = 1;
    sax_detail_reset_pool();
    return 0;
}

int sax_detail_structure_count(void)
{
    return 26;
}

static void fillPiece(SaxPiece *dst, const Piece *p)
{
    memset(dst, 0, sizeof(*dst));
    dst->posX = p->pos.x;
    dst->posY = p->pos.y;
    dst->posZ = p->pos.z;
    dst->bb0x = p->bb0.x; dst->bb0y = p->bb0.y; dst->bb0z = p->bb0.z;
    dst->bb1x = p->bb1.x; dst->bb1y = p->bb1.y; dst->bb1z = p->bb1.z;
    dst->rot = p->rot;
    dst->type = p->type;
    dst->chestCount = p->chestCount;
    dst->nameOff = intern(p->name);
    for (int i = 0; i < p->chestCount && i < SAX_MAX_CHESTS; i++) {
        dst->chestPos[i][0] = p->chestPoses[i].x;
        dst->chestPos[i][1] = 0; /* Pos has no Y; chest Y is unknown here */
        dst->chestPos[i][2] = p->chestPoses[i].z;
        dst->lootSeed[i] = p->lootSeeds[i];
        dst->tableOff[i] = intern(p->lootTables[i]);
    }
}

int sax_detail_pieces(int stype, int regX, int regZ, SaxPiece *out, int outCapacity)
{
    if (!ready || !out || outCapacity <= 0)
        return -1;

    Pos pos;
    if (!getStructurePos(stype, curMc, curSeed, regX, regZ, &pos))
        return -2;

    StructureSaltConfig ssc;
    int biome = 0;
    if (stype == End_City)
        biome = end_highlands;
    else if (stype == Abandoned_Camp)
        biome = getBiomeAt(&g, 1, pos.x, 320, pos.z);
    if (!getStructureSaltConfig(stype, curMc, biome, &ssc))
        return -3;

    StructureVariant sv;
    memset(&sv, 0, sizeof(sv));
    getVariant(&sv, stype, curMc, curSeed, pos.x, pos.z, 0);

    static Piece list[MAX_PIECES];
    memset(list, 0, sizeof(list));

    int n = getStructurePieces(list, MAX_PIECES, stype, ssc, &sv, curMc, curSeed, pos.x, pos.z);
    if (n <= 0)
        return 0;
    if (n > outCapacity)
        n = outCapacity;

    for (int i = 0; i < n; i++)
        fillPiece(&out[i], &list[i]);
    return n;
}

int sax_detail_stronghold(SaxPiece *out, int outCapacity)
{
    if (!ready || !out || outCapacity <= 0)
        return -1;

    StrongholdIter sh;
    initFirstStronghold(&sh, curMc, curSeed);
    if (!nextStronghold(&sh, &g))
        return -2;

    StructureSaltConfig ssc;
    if (!getStructureSaltConfig(Stronghold, curMc, 0, &ssc))
        return -3;

    static Piece list[MAX_PIECES];
    memset(list, 0, sizeof(list));

    int cx = sh.pos.x >> 4, cz = sh.pos.z >> 4;
    int n = getStrongholdLoot(list, MAX_PIECES, ssc, curMc, curSeed, cx, cz);
    if (n <= 0)
        return 0;
    if (n > outCapacity)
        n = outCapacity;

    for (int i = 0; i < n; i++)
        fillPiece(&out[i], &list[i]);
    return n;
}

int sax_detail_loot(const char *table, uint64_t lootSeed, int mcVersion,                    SaxLootItem *out, int maxItems)
{
    if (!table || !out || maxItems <= 0)
        return -1;
    int mc = mcVersion < 0 ? curMc : mcVersion;

    LootTableContext *ctx = NULL;
    if (!init_loot_table_name(&ctx, table, mc))
        return -2;

    set_loot_seed(ctx, lootSeed);
    generate_loot(ctx);

    int n = ctx->generated_item_count;
    if (n > maxItems)
        n = maxItems;
    if (n > 27)
        n = 27;

    for (int i = 0; i < n; i++) {
        ItemStack *it = &ctx->generated_items[i];
        SaxLootItem *dst = &out[i];
        memset(dst, 0, sizeof(*dst));
        dst->count = it->count;
        dst->itemOff = intern(get_item_name(ctx, it->item));
        int ec = it->enchantment_count;
        if (ec > 6) ec = 6;
        dst->enchCount = ec;
        for (int e = 0; e < ec; e++) {
            dst->enchId[e] = it->enchantments[e].enchantment;
            dst->enchLevel[e] = it->enchantments[e].level;
        }
    }
    return n;
}

int sax_detail_structure_at(int stype, int blockX, int blockZ, SaxPiece *out, int cap)
{
    if (!ready || !out || cap <= 0)
        return -1;
    if (stype == Stronghold)
        return sax_detail_stronghold(out, cap);

    StructureConfig sc;
    if (!getStructureConfig(stype, curMc, &sc))
        return -4;
    int rs = sc.regionSize * 16;
    if (rs <= 0)
        return -4;

    int rx = blockX >= 0 ? blockX / rs : (blockX - rs + 1) / rs;
    int rz = blockZ >= 0 ? blockZ / rs : (blockZ - rs + 1) / rs;

    long best = -1;
    int bestX = 0, bestZ = 0;
    for (int dx = -1; dx <= 1; dx++) {
        for (int dz = -1; dz <= 1; dz++) {
            Pos p;
            if (!getStructurePos(stype, curMc, curSeed, rx + dx, rz + dz, &p))
                continue;
            long ddx = (long)p.x - blockX;
            long ddz = (long)p.z - blockZ;
            long d = ddx * ddx + ddz * ddz;
            if (best < 0 || d < best) {
                best = d;
                bestX = rx + dx;
                bestZ = rz + dz;
            }
        }
    }
    if (best < 0)
        return -5;
    return sax_detail_pieces(stype, bestX, bestZ, out, cap);
}
