package org.seedatlas.xaero.gui;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import org.seedatlas.xaero.config.MarkerType;
import org.seedatlas.xaero.detail.StructureDetail;
import org.seedatlas.xaero.detail.StructureTypes;
import org.seedatlas.xaero.integration.icon.StructureIcons;
import net.minecraft.client.renderer.RenderPipelines;

/**
 * Two-pane structure browser: pick a structure type on the left, read its pieces, containers and
 * predicted loot on the right. Mirrors the launcher's seed map: everything reachable by clicking.
 */
public final class StructureDetailScreen extends SeedAtlasScreen {
    private static final int PANE_WIDTH = 148;
    private static final int ROW_HEIGHT = 18;
    private static final int LINE_HEIGHT = 10;
    private static final int PAD = 8;

    private record Entry(MarkerType type, StructureTypes.Mapping mapping, StructureIcons.Icon icon) {}

    private final List<Entry> entries = new ArrayList<>();
    private final List<String> lines = new ArrayList<>();
    private final List<Integer> lineColors = new ArrayList<>();
    private final List<Integer> lineIndent = new ArrayList<>();

    private int selected = -1;
    private int listScroll;
    private int detailScroll;
    private long seed;
    private int playerX;
    private int playerZ;
    private int dimension;

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
            .bounds(PANE_WIDTH + PAD, this.height - 26, 90, 20).build());
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
        lineColors.clear();
        lineIndent.clear();
        if (index < 0 || index >= entries.size()) return;
        Entry entry = entries.get(index);
        StructureDetail.Result result = StructureDetail.load(seed, entry.mapping().stype(), playerX, playerZ, entry.mapping().dimension());
        if (!result.ok()) {
            add(result.error(), 0xFFE06060, 0);
            return;
        }
        add(entry.mapping().label() + " · " + result.pieces().size() + " 块 · "
            + result.pieces().stream().mapToInt(p -> p.chests().size()).sum() + " 箱", 0xFFFFD24A, 0);
        add("", 0xFFDDDDDD, 0);
        if (result.pieces().isEmpty()) {
            add("附近没有找到该结构。", 0xFF999999, 0);
            return;
        }
        for (StructureDetail.Piece piece : result.pieces()) {
            add("▸ " + piece.name() + "   " + piece.x() + " " + piece.z(), 0xFF9FD8FF, 0);
            for (StructureDetail.Chest chest : piece.chests()) {
                add("▣ " + org.seedatlas.xaero.detail.Localize.table(chest.table()) + "   @" + chest.x() + " " + chest.z(), 0xFFBFBFBF, 2);
                for (StructureDetail.Loot loot : chest.items()) {
                    String suffix = loot.enchantments().isEmpty() ? "" : "  [" + String.join(", ", loot.enchantments()) + "]";
                    add(loot.itemId() + " ×" + loot.count() + suffix, 0xFFE8E8E8, 6);
                }
            }
        }
    }

    private static String shortTable(String table) {
        int slash = table.lastIndexOf('/');
        return slash >= 0 ? table.substring(slash + 1) : table;
    }

    private void add(String text, int color, int indent) {
        lines.add(text);
        lineColors.add(color);
        lineIndent.add(indent);
    }

    private void copySelected() {
        if (selected < 0 || selected >= entries.size()) return;
        var result = StructureDetail.load(seed, entries.get(selected).mapping().stype(), playerX, playerZ, entries.get(selected).mapping().dimension());
        if (result.pieces().isEmpty()) return;
        var p = result.pieces().get(0);
        Minecraft.getInstance().keyboardHandler.setClipboard("X: " + p.x() + ", Z: " + p.z());
    }

    @Override
    public boolean mouseClicked(final net.minecraft.client.input.MouseButtonEvent event, final boolean doubled) {
        int mx = (int) event.x();
        int my = (int) event.y();
        if (mx < PANE_WIDTH && my > 28 && my < this.height - 4) {
            int index = listScroll + (my - 28) / ROW_HEIGHT;
            if (index >= 0 && index < entries.size()) {
                select(index);
                return true;
            }
        }
        return super.mouseClicked(event, doubled);
    }

    @Override
    public boolean mouseScrolled(final double mouseX, final double mouseY, final double scrollX, final double scrollY) {
        if (mouseX < PANE_WIDTH) {
            int visible = Math.max(1, (this.height - 34) / ROW_HEIGHT);
            int maxScroll = Math.max(0, entries.size() - visible);
            listScroll = clamp(listScroll - (int) Math.signum(scrollY), 0, maxScroll);
        } else {
            int visible = Math.max(1, (this.height - 64) / LINE_HEIGHT);
            int maxScroll = Math.max(0, lines.size() - visible);
            detailScroll = clamp(detailScroll - (int) Math.signum(scrollY) * 2, 0, maxScroll);
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

        // left pane
        graphics.fill(0, 24, PANE_WIDTH, this.height, 0xC0181818);
        graphics.text(font, Component.literal("结构类型"), PAD, 12, 0xFFFFFFFF);
        int rowY = 28;
        for (int i = listScroll; i < entries.size() && rowY < this.height - 24; i++, rowY += ROW_HEIGHT) {
            boolean hovered = mouseX < PANE_WIDTH && mouseY >= rowY && mouseY < rowY + ROW_HEIGHT;
            if (i == selected) graphics.fill(0, rowY, PANE_WIDTH, rowY + ROW_HEIGHT, 0xFF2F4F6F);
            else if (hovered) graphics.fill(0, rowY, PANE_WIDTH, rowY + ROW_HEIGHT, 0x40FFFFFF);
            Entry entry = entries.get(i);
            if (entry.icon() != null) {
                var layout = entry.icon().layout();
                int iconX = PAD;
                int iconY = rowY + (ROW_HEIGHT - layout.displayHeight()) / 2;
                graphics.blit(RenderPipelines.GUI_TEXTURED, entry.icon().texture(), iconX, iconY,
                    (float) layout.u(), (float) layout.v(), layout.displayWidth(), layout.displayHeight(),
                    layout.width(), layout.height(), layout.textureWidth(), layout.textureHeight());
            }
            graphics.text(font, Component.literal(entry.mapping().label()), PAD + 20, rowY + 5,
                i == selected ? 0xFFFFFFFF : 0xFFCCCCCC);
        }
        graphics.fill(PANE_WIDTH, 24, PANE_WIDTH + 1, this.height, 0xFF444444);

        // right pane
        int x = PANE_WIDTH + PAD;
        int y = 30;
        int visible = Math.max(1, (this.height - 64) / LINE_HEIGHT);
        int end = Math.min(lines.size(), detailScroll + visible);
        for (int i = detailScroll; i < end; i++, y += LINE_HEIGHT) {
            graphics.text(font, Component.literal(lines.get(i)), x + lineIndent.get(i), y, lineColors.get(i));
        }
        String footer = lines.size() > visible
            ? "滚轮翻页 · " + (detailScroll + 1) + "-" + end + " / " + lines.size()
            : "共 " + lines.size() + " 行";
        graphics.text(font, Component.literal(footer), x, this.height - 22, 0xFF999999);
    }

    @Override
    public void onClose() {
        this.minecraft.gui.setScreen(parent);
    }
}
