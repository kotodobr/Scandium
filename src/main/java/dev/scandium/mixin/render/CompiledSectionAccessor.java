package dev.scandium.mixin.render;

import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Set;

/** The set of non-empty layers of a compiled section (vanilla only offers isEmpty(layer) one at a time). */
@Mixin(SectionRenderDispatcher.CompiledSection.class)
public interface CompiledSectionAccessor {
    @Accessor("hasBlocks")
    Set<RenderType> scandium$getNonEmptyLayers();
}
