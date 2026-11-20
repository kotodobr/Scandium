package dev.scandium.mixin.physics;

import dev.scandium.config.ScandiumConfig;
import net.minecraft.core.SectionPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Every tick, {@code checkInsideBlocks} walks every block inside an entity's box and calls
 * {@code BlockState.entityInside}. Most of the time the entity is moving through air, and the walk is done anyway.
 * A 16x16x16 section knows whether it holds only air: if every touched section does, there is nothing to find
 * and the result is guaranteed identical. Ported from Vodium.
 */
@Mixin(Entity.class)
public abstract class EntityInsideBlocksMixin {
    @Shadow
    public abstract Level level();

    @Shadow
    public abstract AABB getBoundingBox();

    @Inject(method = "checkInsideBlocks", at = @At("HEAD"), cancellable = true)
    private void scandium$skipAirSections(CallbackInfo ci) {
        if (!ScandiumConfig.get().fastBlockCollisionActive()) {
            return;
        }
        Level level = this.level();
        AABB box = this.getBoundingBox();

        // Same inset as vanilla, otherwise a boundary section could be missed.
        int minX = SectionPos.posToSectionCoord(box.minX + 1.0E-7);
        int minY = SectionPos.posToSectionCoord(box.minY + 1.0E-7);
        int minZ = SectionPos.posToSectionCoord(box.minZ + 1.0E-7);
        int maxX = SectionPos.posToSectionCoord(box.maxX - 1.0E-7);
        int maxY = SectionPos.posToSectionCoord(box.maxY - 1.0E-7);
        int maxZ = SectionPos.posToSectionCoord(box.maxZ - 1.0E-7);

        for (int chunkX = minX; chunkX <= maxX; chunkX++) {
            for (int chunkZ = minZ; chunkZ <= maxZ; chunkZ++) {
                ChunkAccess chunk = level.getChunk(chunkX, chunkZ, ChunkStatus.FULL, false);
                if (chunk == null) {
                    return; // not loaded: let vanilla decide
                }
                LevelChunkSection[] sections = chunk.getSections();
                for (int sectionY = minY; sectionY <= maxY; sectionY++) {
                    int index = chunk.getSectionIndexFromSectionY(sectionY);
                    if (index < 0 || index >= sections.length) {
                        continue;
                    }
                    if (!sections[index].hasOnlyAir()) {
                        return;
                    }
                }
            }
        }
        ci.cancel();
    }
}
