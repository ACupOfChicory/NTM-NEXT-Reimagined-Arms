package acup.gunfeel.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.client.KeyMapping;

@Mixin(KeyMapping.class)
public interface KeyMappingAccess {
    @Accessor("isDown")
    void gunfeel$setIsDown(boolean down);
}
