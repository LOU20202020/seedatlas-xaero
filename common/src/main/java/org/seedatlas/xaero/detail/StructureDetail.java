package org.seedatlas.xaero.detail;

import java.io.InputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Optional bridge to the bundled sax_detail native library, which predicts structure pieces,
 * container positions and chest loot. Every entry point fails soft so the map keeps working
 * when the library is missing or the platform is unsupported.
 */
public final class StructureDetail {
    private static final Logger LOGGER = LoggerFactory.getLogger("seedatlas_xaero");
    private static final int MAX_PIECES = 600;
    private static final int MAX_ITEMS = 27;

    /** Mirrors SaxPiece in sax_detail.h: 24 ints, 4 seeds, 5 ints, 4 bytes tail padding. */
    private static final MemoryLayout PIECE_LAYOUT = MemoryLayout.structLayout(
        MemoryLayout.sequenceLayout(24, ValueLayout.JAVA_INT),
        MemoryLayout.sequenceLayout(4, ValueLayout.JAVA_LONG),
        MemoryLayout.sequenceLayout(5, ValueLayout.JAVA_INT),
        MemoryLayout.paddingLayout(4)
    );
    private static final long PIECE_INTS_OFF = 0L;
    private static final long PIECE_SEEDS_OFF = 96L;
    private static final long PIECE_TAIL_OFF = 128L;

    /** Mirrors SaxLootItem: count, itemOff, enchCount, enchId[6], enchLevel[6]. */
    private static final MemoryLayout ITEM_LAYOUT = MemoryLayout.structLayout(
        MemoryLayout.sequenceLayout(3, ValueLayout.JAVA_INT),
        MemoryLayout.sequenceLayout(6, ValueLayout.JAVA_INT),
        MemoryLayout.sequenceLayout(6, ValueLayout.JAVA_INT)
    );
    private static final long ITEM_HEAD_OFF = 0L;
    private static final long ITEM_ENCH_ID_OFF = 12L;
    private static final long ITEM_ENCH_LVL_OFF = 36L;

    /** id = raw registry id ("minecraft:diamond_sword"), name = in-game display name, enchantments = localized "锋利 3". */
    public record Loot(String id, String name, int count, List<String> enchantments) {}
    public record Chest(int x, int y, int z, String table, List<Loot> items) {}
    public record Piece(String name, int x, int y, int z, List<Chest> chests) {}
    public record Result(String header, List<Piece> pieces, String error) {
        public static Result failed(String message) { return new Result("", List.of(), message); }
        public boolean ok() { return error == null; }
    }

    private static boolean attempted;
    private static boolean unavailable;
    private static String unavailableReason = "";
    private static Arena arena;
    private static MethodHandle init;
    private static MethodHandle stronghold;
    private static MethodHandle structureAt;
    private static MethodHandle piecesAt;
    private static MethodHandle strongholdAt;
    private static MethodHandle string;
    private static MethodHandle loot;
    private static MethodHandle enchantName;

    private StructureDetail() {}

    private static synchronized boolean ensureLoaded() {
        if (attempted) return !unavailable;
        attempted = true;
        try {
            Path lib = extract();
            arena = Arena.ofShared();
            SymbolLookup symbols = SymbolLookup.libraryLookup(lib, arena);
            Linker linker = Linker.nativeLinker();
            init = downcall(linker, symbols, "sax_detail_init",
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
            stronghold = downcall(linker, symbols, "sax_detail_stronghold",
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
            string = downcall(linker, symbols, "sax_detail_string",
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
            loot = downcall(linker, symbols, "sax_detail_loot",
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG,
                    ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
            structureAt = downcall(linker, symbols, "sax_detail_structure_at",
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                    ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
            piecesAt = downcall(linker, symbols, "sax_detail_pieces",
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                    ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
            strongholdAt = downcall(linker, symbols, "sax_detail_stronghold_at",
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
            enchantName = downcall(linker, symbols, "sax_detail_enchant_name",
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
            LOGGER.info("Seed Atlas structure detail library loaded from {}", lib);
            return true;
        } catch (Throwable error) {
            unavailable = true;
            unavailableReason = String.valueOf(error);
            LOGGER.warn("Structure detail library unavailable: {}", unavailableReason);
            return false;
        }
    }

    private static MethodHandle downcall(Linker linker, SymbolLookup symbols, String name, FunctionDescriptor descriptor) {
        MemorySegment symbol = symbols.find(name)
            .orElseThrow(() -> new IllegalStateException("missing native symbol " + name));
        return linker.downcallHandle(symbol, descriptor);
    }

    private static Path extract() throws Exception {
        String resource = "/natives/windows-x86_64/sax_detail.dll";
        Path cache = Path.of(System.getProperty("java.io.tmpdir"), "seedatlas-xaero-detail");
        Path target = cache.resolve("sax_detail.dll");
        Files.createDirectories(cache);
        try (InputStream input = StructureDetail.class.getResourceAsStream(resource)) {
            if (input == null) throw new IllegalStateException("bundled native missing: " + resource);
            byte[] bytes = input.readAllBytes();
            boolean stale = !Files.isRegularFile(target) || Files.size(target) != bytes.length;
            if (!stale) {
                stale = !java.util.Arrays.equals(Files.readAllBytes(target), bytes);
            }
            if (stale) {
                Files.write(target, bytes);
            }
        }
        return target;
    }

    public static String status() {
        if (!ensureLoaded()) return "结构详情不可用：" + unavailableReason;
        return "结构详情引擎已加载";
    }

    /** Predicts the structure nearest to (x, z) for the given cubiomes structure type. */
    /** Nearest structure of this type around the given block position. */
    public static Result load(long seed, int stype, int x, int z, int dim) {
        return query(seed, stype, dim, buffer -> (int) structureAt.invokeExact(stype, x, z, buffer, MAX_PIECES));
    }

    /**
     * Exactly the structure of the given region - used when the player right-clicks a map marker,
     * so the panel describes that marker instead of whichever neighbour happens to be closer.
     */
    /** The stronghold nearest to a marker: strongholds are not laid out on a region grid. */
    public static Result loadStronghold(long seed, int blockX, int blockZ, int dim) {
        return query(seed, 27, dim,
            buffer -> (int) strongholdAt.invokeExact(blockX, blockZ, buffer, MAX_PIECES));
    }

    public static Result loadRegion(long seed, int stype, int regionX, int regionZ, int dim) {
        return query(seed, stype, dim,
            buffer -> (int) piecesAt.invokeExact(stype, regionX, regionZ, buffer, MAX_PIECES));
    }

    private interface PieceQuery {
        int run(MemorySegment buffer) throws Throwable;
    }

    private static Result query(long seed, int stype, int dim, PieceQuery pieceQuery) {
        if (!ensureLoaded()) return Result.failed("结构详情引擎未加载：" + unavailableReason);
        try (Arena local = Arena.ofConfined()) {
            int rc = (int) init.invokeExact(seed, -1, dim);
            if (rc != 0) return Result.failed("初始化失败（" + rc + "）");

            MemorySegment buffer = local.allocate(PIECE_LAYOUT, MAX_PIECES);
            int count = pieceQuery.run(buffer);
            if (count < 0) return Result.failed("没有找到该结构（" + count + "）");

            List<Piece> pieces = new ArrayList<>();
            int chests = 0;
            for (int i = 0; i < count; i++) {
                MemorySegment piece = buffer.asSlice(i * PIECE_LAYOUT.byteSize(), PIECE_LAYOUT);
                MemorySegment tail = piece.asSlice(PIECE_TAIL_OFF, 20);
                int chestCount = pieceInt(piece, 11);
                String name = readString(tail.getAtIndex(ValueLayout.JAVA_INT, 4));
                int px = pieceInt(piece, 0);
                int py = pieceInt(piece, 1);
                int pz = pieceInt(piece, 2);
                List<Chest> chestList = new ArrayList<>();
                for (int c = 0; c < chestCount && c < 4; c++) {
                    int tableOff = tail.getAtIndex(ValueLayout.JAVA_INT, c);
                    if (tableOff == 0) continue;
                    String table = readString(tableOff);
                    long lootSeed = piece.asSlice(PIECE_SEEDS_OFF, 32).getAtIndex(ValueLayout.JAVA_LONG, c);
                    int cx = pieceInt(piece, 12 + c * 3);
                    int cz = pieceInt(piece, 12 + c * 3 + 2);
                    chestList.add(new Chest(cx, py, cz, table, rollLoot(local, table, lootSeed)));
                    chests++;
                }
                if (!chestList.isEmpty()) pieces.add(new Piece(name, px, py, pz, chestList));
            }
            String header = "结构块 " + count + " · 箱子 " + chests;
            return new Result(header, pieces, null);
        } catch (Throwable error) {
            LOGGER.warn("Structure detail query failed", error);
            return Result.failed("查询失败：" + error);
        }
    }

    private static int pieceInt(MemorySegment piece, int index) {
        return piece.asSlice(PIECE_INTS_OFF, 96).getAtIndex(ValueLayout.JAVA_INT, index);
    }

    private static List<Loot> rollLoot(Arena arena, String table, long lootSeed) throws Throwable {
        MemorySegment items = arena.allocate(ITEM_LAYOUT, MAX_ITEMS);
        MemorySegment tableName = arena.allocateFrom(table);
        int n = (int) loot.invokeExact(tableName, lootSeed, -1, items, MAX_ITEMS);
        if (n <= 0) return List.of();

        List<Loot> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            MemorySegment item = items.asSlice(i * ITEM_LAYOUT.byteSize(), ITEM_LAYOUT);
            MemorySegment head = item.asSlice(ITEM_HEAD_OFF, 12);
            int count = head.getAtIndex(ValueLayout.JAVA_INT, 0);
            int itemOff = head.getAtIndex(ValueLayout.JAVA_INT, 1);
            int ec = head.getAtIndex(ValueLayout.JAVA_INT, 2);
            MemorySegment ids = item.asSlice(ITEM_ENCH_ID_OFF, 24);
            MemorySegment levels = item.asSlice(ITEM_ENCH_LVL_OFF, 24);
            List<String> ench = new ArrayList<>();
            for (int e = 0; e < ec && e < 6; e++) {
                ench.add(Localize.enchant(readEnchant(ids.getAtIndex(ValueLayout.JAVA_INT, e)))
                    + " " + levels.getAtIndex(ValueLayout.JAVA_INT, e));
            }
            String rawId = readString(itemOff);
            out.add(new Loot(rawId, Localize.item(rawId), count, ench));
        }
        return out;
    }

    private static String readString(int off) {
        if (off <= 0) return "";
        try {
            MemorySegment ptr = (MemorySegment) string.invokeExact(off);
            return ptr.reinterpret(256).getString(0);
        } catch (Throwable error) {
            return "";
        }
    }

    private static String readEnchant(int id) {
        try {
            MemorySegment ptr = (MemorySegment) enchantName.invokeExact(id);
            return ptr.reinterpret(128).getString(0);
        } catch (Throwable error) {
            return "附魔" + id;
        }
    }

    private static String prettyItem(String id) {
        int colon = id.indexOf(':');
        return (colon >= 0 ? id.substring(colon + 1) : id).replace('_', ' ');
    }
}
