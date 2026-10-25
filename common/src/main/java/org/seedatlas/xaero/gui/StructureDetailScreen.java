package org.seedatlas.xaero.gui;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.seedatlas.xaero.config.MarkerType;
import org.seedatlas.xaero.detail.Localize;
import org.seedatlas.xaero.detail.StructureDetail;
import org.seedatlas.xaero.detail.StructureTypes;
import org.seedatlas.xaero.integration.icon.StructureIcons;
import org.seedatlas.xaero.integration.marker.SeedAtlasWaypointBridge;
import net.minecraft.client.renderer.RenderPipelines;

/**
 * Structure inspector. Left pane picks the type, the right pane walks pieces -> containers -> loot
 * as item icons; hovering an icon shows its real name (and, for enchanted books, the enchantments),
 * and every container can be dropped onto the map as a waypoint.
 *
 * <p>Opened from a marker it reports <em>that</em> structure: the marker's own coordinates are used
 * for the lookup instead of the player's position.</p>
 */
public final class StructureDetailScreen extends SeedAtlasScreen {
    private static final org.slf4j.Logger LOGGER =
        org.slf4j.LoggerFactory.getLogger("seedatlas_xaero");
    private static final int PANE_WIDTH = 148;
    private static final int ROW_HEIGHT = 18;
    private static final int PAD = 8;
    private static final int SLOT = 18;
    private static final int PER_ROW = 8;
    private static final int CONTENT_TOP = 34;

    private record Entry(MarkerType type, StructureTypes.Mapping mapping, StructureIcons.Icon icon) {}

    /** One rendered line: a piece header, a container header, or an error. */
    private record Line(String text, int color, int y, int height, int chestIndex) {}

    private record Slot(int x, int y, StructureDetail.Loot loot) {}

    private final List<Entry> entries = new ArrayList<>();
    private final List<Line> lines = new ArrayList<>();
    private final List<Slot> slots = new ArrayList<>();
    private final List<int[]> markButtons = new ArrayList<>(); // x, y, w, h, chestIndex
    private final List<StructureDetail.Chest> chestOrder = new ArrayList<>();
    private final Set<Integer> marked = new HashSet<>();

    private int paneWidth() {
        return hasTarget ? 0 : PANE_WIDTH;
    }

    private int selected = -1;
    private int listScroll;
    private int detailScroll;
    private int contentHeight;
    private long seed;
    private int playerX;
    private int playerZ;
    private int dimension;
    private boolean hasTarget;
    private int targetX;
    private int targetZ;
    private String header = "";
    private String markStatus = "";
    private StructureDetail.Result result;

    public StructureDetailScreen(final net.minecraft.client.gui.screens.Screen parent) {
        super(Component.literal("结构详情"), parent);
        for (MarkerType type : MarkerType.values()) {
            StructureTypes.Mapping mapping = StructureTypes.of(type);
            if (mapping != null) entries.add(new Entry(type, mapping, StructureIcons.get(type.id())));
        }
        var player = Minecraft.getInstance().player;
        if (player != null) {
            playerX = (int) player.getX();
            playerZ = (int) player.getZ();
            dimension = player.level().dimension().equals(net.minecraft.world.level.Level.NETHER) ? -1
                : player.level().dimension().equals(net.minecraft.world.level.Level.END) ? 1 : 0;
        }
    }

    public StructureDetailScreen withSeed(long seed) {
        this.seed = seed;
        return this;
    }

    /** The exact structure that was right-clicked, not the one nearest the player. */
    public StructureDetailScreen withTarget(int x, int z) {
        this.hasTarget = true;
        this.targetX = x;
        this.targetZ = z;
        return this;
    }

    /** Opens on the structure type of the marker that was right-clicked. */
    public StructureDetailScreen withType(final MarkerType type) {
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).type() == type) {
                select(i);
                break;
            }
        }
        return this;
    }

    @Override
    protected void init() {
        super.init();
        this.addRenderableWidget(Button.builder(Component.literal("复制坐标"), b -> copySelected())
            .bounds(paneWidth() + PAD, this.height - 26, 90, 20).build());
        this.addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> this.onClose())
            .bounds(this.width - 68, 6, 60, 20).build());
        if (selected < 0 && !entries.isEmpty()) {
            select(0);
        }
    }

    private void select(int index) {
        selected = index;
        detailScroll = 0;
        lines.clear();
        slots.clear();
        markButtons.clear();
        chestOrder.clear();
        marked.clear();
        header = "";
        contentHeight = 0;
        if (index < 0 || index >= entries.size()) return;
        Entry entry = entries.get(index);
        result = loadFor(entry);
        if (!result.ok()) {
            lines.add(new Line(result.error(), 0xFFE06060, 0, 12, -1));
            return;
        }
        int chests = result.pieces().stream().mapToInt(p -> p.chests().size()).sum();
        header = entry.mapping().label() + " · " + result.pieces().size() + " 块 · " + chests + " 箱";
        if (result.pieces().isEmpty()) {
            lines.add(new Line("附近没有找到该结构", 0xFF999999, 0, 12, -1));
            return;
        }
        int y = 0;
        int chestIndex = 0;
        for (StructureDetail.Piece piece : result.pieces()) {
            lines.add(new Line("▸ " + piece.name() + "   " + piece.x() + " " + piece.z(), 0xFF9FD8FF, y, 12, -1));
            y += 12;
            for (StructureDetail.Chest chest : piece.chests()) {
                lines.add(new Line("▣ " + Localize.table(chest.table()) + "   @" + chest.x() + " " + chest.z(),
                    0xFFBFBFBF, y, 14, chestIndex));
                chestOrder.add(chest);
                y += 14;
                int rows = Math.max(1, (chest.items().size() + PER_ROW - 1) / PER_ROW);
                y += rows * SLOT + 2;
                chestIndex++;
            }
        }
        contentHeight = y;
    }

    /** The clicked marker's own structure; falls back to a nearest lookup without a target. */
    private StructureDetail.Result loadFor(final Entry entry) {
        StructureTypes.Mapping mapping = entry.mapping();
        if (mapping.stype() == 27 && hasTarget) {
            return StructureDetail.loadStronghold(seed, targetX, targetZ, mapping.dimension());
        }
        if (!hasTarget) {
            return StructureDetail.load(seed, mapping.stype(), playerX, playerZ, mapping.dimension());
        }
        int regionBlocks = Math.max(16, mapping.regionSize() * 16);
        int rx = Math.floorDiv(targetX, regionBlocks);
        int rz = Math.floorDiv(targetZ, regionBlocks);
        return StructureDetail.loadRegion(seed, mapping.stype(), rx, rz, mapping.dimension());
    }

    private void copySelected() {
        if (selected < 0 || selected >= entries.size()) return;
        var result = loadFor(entries.get(selected));
        if (result.pieces().isEmpty()) return;
        var p = result.pieces().get(0);
        Minecraft.getInstance().keyboardHandler.setClipboard("X: " + p.x() + ", Z: " + p.z());
    }

    /** Rebuilds slot and button hitboxes for the current scroll offset. */
    private void layout() {
        slots.clear();
        markButtons.clear();
        if (selected < 0 || selected >= entries.size()) return;
        int base = CONTENT_TOP - detailScroll;
        if (result == null || !result.ok()) return;
        int y = 0;
        int chestIndex = 0;
        for (StructureDetail.Piece piece : result.pieces()) {
            y += 12;
            for (StructureDetail.Chest chest : piece.chests()) {
                int rowY = base + y;
                markButtons.add(new int[] {this.width - 82, rowY - 1, 76, 15, chestIndex});
                y += 14;
                for (int i = 0; i < chest.items().size(); i++) {
                    int sx = paneWidth() + PAD + 6 + (i % PER_ROW) * SLOT;
                    int sy = base + y + (i / PER_ROW) * SLOT;
                    slots.add(new Slot(sx, sy, chest.items().get(i)));
                }
                y += Math.max(1, (chest.items().size() + PER_ROW - 1) / PER_ROW) * SLOT + 2;
                chestIndex++;
            }
        }
    }

    private static ItemStack stackOf(StructureDetail.Loot loot) {
        try {
            String raw = loot.id();
            // Loot tables hand out a plain book plus stored enchantments; the game shows that as
            // an enchanted book, so mirror it instead of drawing a normal book.
            if (raw != null && raw.endsWith(":book") && !loot.enchantments().isEmpty()) {
                raw = "minecraft:enchanted_book";
            }
            Identifier id = Identifier.tryParse(raw);
            if (id != null) {
                Item item = BuiltInRegistries.ITEM.getValue(id);
                if (item != null) return new ItemStack(item, Math.max(1, Math.min(64, loot.count())));
            }
        } catch (RuntimeException ignored) {
            // fall through
        }
        return ItemStack.EMPTY;
    }

    /** Books: the loot engine rolls the enchantment itself and can land on a different one in game. */
    private static boolean isPredictedBook(StructureDetail.Loot loot) {
        return loot.id() != null && loot.id().endsWith(":book") && !loot.enchantments().isEmpty();
    }

    private List<Component> tooltip(StructureDetail.Loot loot) {
        List<Component> out = new ArrayList<>();
        out.add(Component.literal(loot.name()).withStyle(ChatFormatting.WHITE));
        if (isPredictedBook(loot)) {
            out.add(Component.literal("附魔：预测（可能不准）").withStyle(ChatFormatting.YELLOW));
        }
        for (String enchantment : loot.enchantments()) {
            out.add(Component.literal(enchantment).withStyle(ChatFormatting.LIGHT_PURPLE));
        }
        out.add(Component.literal("数量 ×" + loot.count()).withStyle(ChatFormatting.GRAY));
        out.add(Component.literal("点「标记」把这个箱子放到地图上").withStyle(ChatFormatting.DARK_GRAY));
        return out;
    }

    @Override
    public boolean mouseClicked(final net.minecraft.client.input.MouseButtonEvent event, final boolean doubled) {
        int mx = (int) event.x();
        int my = (int) event.y();
        LOGGER.info("detail click at {},{} button={} markBoxes={} slots={} width={} height={}",
            mx, my, event.button(), markButtons.size(), slots.size(), this.width, this.height);
        if (!hasTarget && mx < PANE_WIDTH && my > 28 && my < this.height - 4) {
            int index = listScroll + (my - 28) / ROW_HEIGHT;
            if (index >= 0 && index < entries.size()) {
                select(index);
                return true;
            }
        }
        for (int[] b : markButtons) {
            if (mx >= b[0] && mx < b[0] + b[2] && my >= b[1] && my < b[1] + b[3]) {
                // any button: a right click on the label was being ignored, which read as "button does nothing"
                markChest(b[4]);
                return true;
            }
        }
        if (event.button() == 0 || event.button() == 1) {
            for (Slot slot : slots) {
                if (mx >= slot.x() && mx < slot.x() + SLOT && my >= slot.y() && my < slot.y() + SLOT) {
                    Minecraft.getInstance().keyboardHandler.setClipboard(itemLine(slot.loot()));
                    return true;
                }
            }
        }
        return super.mouseClicked(event, doubled);
    }

    private static String itemLine(StructureDetail.Loot loot) {
        String suffix = loot.enchantments().isEmpty() ? "" : " [" + String.join(", ", loot.enchantments()) + "]";
        if (isPredictedBook(loot)) suffix += "（附魔为预测，可能不准）";
        return loot.name() + " ×" + loot.count() + suffix;
    }

    private void markChest(int chestIndex) {
        if (chestIndex < 0 || chestIndex >= chestOrder.size()) return;
        StructureDetail.Chest chest = chestOrder.get(chestIndex);
        String name = Localize.table(chest.table()) + " " + chest.x() + "," + chest.z();
        boolean created = SeedAtlasWaypointBridge.createNamed(chest.x(), chest.y(), chest.z(), name, false, false);
        boolean temporary = false;
        if (!created) {
            temporary = SeedAtlasWaypointBridge.createNamed(chest.x(), chest.y(), chest.z(), name, true, false);
        }
        LOGGER.info("markChest table={} at {},{},{} permanent={} temporary={}",
            chest.table(), chest.x(), chest.y(), chest.z(), created, temporary);
        if (created || temporary || marked.contains(chestIndex)) {
            marked.add(chestIndex);
            markStatus = "已在地图上标记：" + name;
        } else {
            markStatus = "标记失败：Xaero 路点接口没响应（" + name + "）";
        }
    }

    @Override
    public boolean mouseScrolled(final double mouseX, final double mouseY, final double scrollX, final double scrollY) {
        if (!hasTarget && mouseX < PANE_WIDTH) {
            int visible = Math.max(1, (this.height - 34) / ROW_HEIGHT);
            int maxScroll = Math.max(0, entries.size() - visible);
            listScroll = clamp(listScroll - (int) Math.signum(scrollY), 0, maxScroll);
        } else {
            int visible = this.height - CONTENT_TOP - 30;
            int maxScroll = Math.max(0, contentHeight - visible);
            detailScroll = clamp(detailScroll - (int) Math.signum(scrollY) * 16, 0, maxScroll);
            layout();
        }
        return true;
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    @Override
    public void extractRenderState(final GuiGraphicsExtractor graphics, final int mouseX, final int mouseY, final float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        var font = this.font;
        layout();

        // left pane (hidden when the panel was opened from a specific marker)
        if (!hasTarget) {
        graphics.fill(0, 24, PANE_WIDTH, this.height, 0xC0181818);
        graphics.text(font, Component.literal("结构类型"), PAD, 12, 0xFFFFFFFF);
        int rowY = 28;
        for (int i = listScroll; i < entries.size() && rowY < this.height - 24; i++, rowY += ROW_HEIGHT) {
            boolean hovered = mouseX < PANE_WIDTH && mouseY >= rowY && mouseY < rowY + ROW_HEIGHT;
            if (i == selected) graphics.fill(0, rowY, PANE_WIDTH, rowY + ROW_HEIGHT, 0xFF2F4F6F);
            else if (hovered) graphics.fill(0, rowY, PANE_WIDTH, rowY + ROW_HEIGHT, 0x40FFFFFF);
            Entry entry = entries.get(i);
            if (entry.icon() != null) {
                var iconLayout = entry.icon().layout();
                graphics.blit(RenderPipelines.GUI_TEXTURED, entry.icon().texture(), PAD,
                    rowY + (ROW_HEIGHT - iconLayout.displayHeight()) / 2,
                    (float) iconLayout.u(), (float) iconLayout.v(), iconLayout.displayWidth(),
                    iconLayout.displayHeight(), iconLayout.width(), iconLayout.height(),
                    iconLayout.textureWidth(), iconLayout.textureHeight());
            }
            graphics.text(font, Component.literal(entry.mapping().label()), PAD + 20, rowY + 5,
                i == selected ? 0xFFFFFFFF : 0xFFCCCCCC);
        }
        graphics.fill(PANE_WIDTH, 24, PANE_WIDTH + 1, this.height, 0xFF444444);
        }

        // detail pane
        int x = paneWidth() + PAD;
        if (!header.isEmpty()) {
            graphics.text(font, Component.literal(header), x, 12, 0xFFFFD24A);
        }
        if (!markStatus.isEmpty()) {
            boolean bad = markStatus.startsWith("标记失败");
            graphics.text(font, Component.literal(markStatus), x, 22, bad ? 0xFFE06060 : 0xFF7FD08A);
        }
        for (Line line : lines) {
            int ly = CONTENT_TOP + line.y() - detailScroll;
            if (ly < 24 || ly > this.height - 30) continue;
            graphics.text(font, Component.literal(line.text()), x, ly, line.color());
            if (line.chestIndex() >= 0) {
                int[] box = null;
                for (int[] b : markButtons) if (b[4] == line.chestIndex()) { box = b; break; }
                if (box != null) {
                    boolean done = marked.contains(line.chestIndex());
                    boolean hover = mouseX >= box[0] && mouseX < box[0] + box[2] && mouseY >= box[1] && mouseY < box[1] + box[3];
                    graphics.fill(box[0], box[1], box[0] + box[2], box[1] + box[3],
                        done ? 0xFF2E5E33 : hover ? 0xFF4A6E9C : 0xFF334155);
                    String label = done ? "已标记" : "标记";
                    graphics.text(font, Component.literal(label), box[0] + (box[2] - font.width(label)) / 2, box[1] + 2, 0xFFFFFFFF);
                }
            }
        }

        // item icons
        StructureDetail.Loot hoveredLoot = null;
        for (Slot slot : slots) {
            if (slot.y() + SLOT < 24 || slot.y() > this.height - 30) continue;
            boolean hover = mouseX >= slot.x() && mouseX < slot.x() + SLOT && mouseY >= slot.y() && mouseY < slot.y() + SLOT;
            graphics.fill(slot.x(), slot.y(), slot.x() + SLOT, slot.y() + SLOT, hover ? 0xFF8A8A8A : 0xFF555555);
            graphics.fill(slot.x() + 1, slot.y() + 1, slot.x() + SLOT - 1, slot.y() + SLOT - 1, 0xFF1B1B1B);
            ItemStack stack = stackOf(slot.loot());
            if (!stack.isEmpty()) {
                graphics.item(stack, slot.x() + 1, slot.y() + 1);
                graphics.itemDecorations(font, stack, slot.x() + 1, slot.y() + 1);
            } else {
                graphics.text(font, Component.literal("?"), slot.x() + 6, slot.y() + 5, 0xFFAAAAAA);
            }
            if (hover) hoveredLoot = slot.loot();
        }
        if (hoveredLoot != null) {
            graphics.setTooltipForNextFrame(font, tooltip(hoveredLoot), Optional.empty(), mouseX, mouseY);
        }

        String footer = contentHeight > 0
            ? "滚轮翻页 · 点物品可复制 · 点箱子图标上的「标记」放到地图"
            : "";
        if (!footer.isEmpty()) graphics.text(font, Component.literal(footer), x, this.height - 22, 0xFF888888);
    }

    @Override
    public void onClose() {
        this.minecraft.gui.setScreen(parent);
    }
}
