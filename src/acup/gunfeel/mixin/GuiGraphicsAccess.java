package acup.gunfeel.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.state.gui.GuiRenderState;

@Mixin(GuiGraphicsExtractor.class)
public interface GuiGraphicsAccess {
    @Accessor("guiRenderState")
    GuiRenderState gunfeel$state();
}
