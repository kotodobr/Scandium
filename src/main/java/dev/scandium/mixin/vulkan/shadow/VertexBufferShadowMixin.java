package dev.scandium.mixin.vulkan.shadow;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.VertexBuffer;
import dev.scandium.vulkan.shadow.ShadowRenderer;
import dev.scandium.vulkan.shadow.ShadowVertexBuffer;
import dev.scandium.vulkan.shadow.VertexMirror;
import net.minecraft.client.renderer.ShaderInstance;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Mirrors every VertexBuffer upload and every shader draw into the Vulkan shadow renderer. */
@Mixin(VertexBuffer.class)
public abstract class VertexBufferShadowMixin implements ShadowVertexBuffer {
    @Shadow
    @Final
    private VertexBuffer.Usage usage;

    @Unique
    private final VertexMirror scandium$mirror = new VertexMirror();

    @Override
    public VertexMirror scandium$mirror() {
        return this.scandium$mirror;
    }

    @Override
    public boolean scandium$isDynamic() {
        return this.usage == VertexBuffer.Usage.DYNAMIC;
    }

    @Inject(method = "upload", at = @At("HEAD"), cancellable = true)
    private void scandium$mirrorUpload(BufferBuilder.RenderedBuffer rendered, CallbackInfo ci) {
        ShadowRenderer.onUpload(this, rendered);
        if (ShadowRenderer.skipGlUpload(this)) {
            rendered.release(); // vanilla releases in its finally block, which no longer runs
            ci.cancel();
        }
    }

    /** Mirror into Vulkan; the GL draw itself only runs while the GL picture is still needed (stage 7). */
    @Redirect(method = "_drawWithShader",
            at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/VertexBuffer;draw()V"))
    private void scandium$mirrorDraw(VertexBuffer self, Matrix4f modelView, Matrix4f projection, ShaderInstance shader) {
        ShadowRenderer.onDraw(this, shader);
        if (!ShadowRenderer.skipGlDraw(this)) {
            self.draw();
        }
    }

    @Inject(method = "close", at = @At("HEAD"))
    private void scandium$mirrorClose(CallbackInfo ci) {
        ShadowRenderer.onClose(this);
    }
}
