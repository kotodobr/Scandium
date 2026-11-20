package dev.scandium.mixin.render;

import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.scandium.config.ScandiumConfig;
import dev.scandium.render.CameraFrustum;
import dev.scandium.render.RenderStats;
import net.minecraft.client.Camera;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Particles outside the frustum don't build geometry. Vanilla never frustum-tests particles: a campfire behind
 * the player costs as much as one in front. Only rendering is skipped - ticking, movement and lifetime are
 * untouched, otherwise off-screen particles would freeze and appear in a burst when turning. Ported from Vodium.
 */
@Mixin(ParticleEngine.class)
public abstract class ParticleCullingMixin {
    @Redirect(
            method = "render",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/particle/Particle;render(Lcom/mojang/blaze3d/vertex/VertexConsumer;Lnet/minecraft/client/Camera;F)V")
    )
    private void scandium$cullParticle(Particle particle, VertexConsumer consumer, Camera camera, float partialTick) {
        ScandiumConfig cfg = ScandiumConfig.get();
        if (cfg.particleCullingActive() && CameraFrustum.isReady()) {
            // The margin matters: the box describes collisions, a particle's quad can be several blocks large.
            if (!CameraFrustum.isVisible(particle.getBoundingBox().inflate(cfg.particleCullMargin))) {
                RenderStats.particleCulled();
                return;
            }
        }
        particle.render(consumer, camera, partialTick);
    }
}
