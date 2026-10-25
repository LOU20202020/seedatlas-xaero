package org.seedatlas.xaero.integration.gui;

import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.seedatlas.xaero.config.SeedAtlasClientState;
import org.seedatlas.xaero.integration.SeedAtlasXaeroIntegration;

/** Compact opacity control embedded in Xaero's full-map toolbar. */
public final class SeedAtlasOpacitySlider extends AbstractSliderButton {
    private boolean dirty;

    public SeedAtlasOpacitySlider(int x, int y, int width, int opacity) {
        super(x, y, width, 20, Component.empty(), Math.clamp(opacity, 0, 255) / 255.0);
        updateMessage();
    }

    @Override
    protected void updateMessage() {
        setMessage(Component.translatable(
            "options.seedatlas_xaero.opacity", Math.round(value * 100.0)));
    }

    @Override
    protected void applyValue() {
        // Dragging updates the label continuously, but disk/cache work is
        // committed only when the interaction finishes.
        this.dirty = true;
    }

    @Override
    public void onRelease(MouseButtonEvent event) {
        super.onRelease(event);
        commitValue();
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        boolean handled = super.keyPressed(event);
        if (handled) {
            commitValue();
        }
        return handled;
    }

    private void commitValue() {
        if (!this.dirty) {
            return;
        }
        this.dirty = false;
        SeedAtlasClientState.setOpacity((int)Math.round(this.value * 255.0));
        SeedAtlasXaeroIntegration.synchronizeRevision();
    }
}
