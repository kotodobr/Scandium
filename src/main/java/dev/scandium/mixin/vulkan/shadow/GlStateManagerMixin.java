package dev.scandium.mixin.vulkan.shadow;

import com.mojang.blaze3d.platform.GlStateManager;
import dev.scandium.vulkan.shadow.GlStateTracker;
import dev.scandium.vulkan.shadow.ShadowRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.IntBuffer;

/**
 * Feeds every GL state change into {@link GlStateTracker} and texture uploads into the shadow renderer. Each hook
 * is a static field write; the plugin only applies this mixin when the Vulkan shadow renderer is enabled.
 */
@Mixin(value = GlStateManager.class, remap = false)
public abstract class GlStateManagerMixin {
    @Inject(method = "_enableBlend", at = @At("HEAD"))
    private static void scandium$enableBlend(CallbackInfo ci) {
        GlStateTracker.blend(true);
    }

    @Inject(method = "_disableBlend", at = @At("HEAD"))
    private static void scandium$disableBlend(CallbackInfo ci) {
        GlStateTracker.blend(false);
    }

    @Inject(method = "_blendFunc", at = @At("HEAD"))
    private static void scandium$blendFunc(int src, int dst, CallbackInfo ci) {
        GlStateTracker.blendFunc(src, dst);
    }

    @Inject(method = "_blendFuncSeparate", at = @At("HEAD"))
    private static void scandium$blendFuncSeparate(int sc, int dc, int sa, int da, CallbackInfo ci) {
        GlStateTracker.blendFuncSeparate(sc, dc, sa, da);
    }

    @Inject(method = "_blendEquation", at = @At("HEAD"))
    private static void scandium$blendEquation(int equation, CallbackInfo ci) {
        GlStateTracker.blendEquation(equation);
    }

    @Inject(method = "_enableDepthTest", at = @At("HEAD"))
    private static void scandium$enableDepth(CallbackInfo ci) {
        GlStateTracker.depthTest(true);
    }

    @Inject(method = "_disableDepthTest", at = @At("HEAD"))
    private static void scandium$disableDepth(CallbackInfo ci) {
        GlStateTracker.depthTest(false);
    }

    @Inject(method = "_depthFunc", at = @At("HEAD"))
    private static void scandium$depthFunc(int func, CallbackInfo ci) {
        GlStateTracker.depthFunc(func);
    }

    @Inject(method = "_depthMask", at = @At("HEAD"))
    private static void scandium$depthMask(boolean mask, CallbackInfo ci) {
        GlStateTracker.depthMask(mask);
    }

    @Inject(method = "_enableCull", at = @At("HEAD"))
    private static void scandium$enableCull(CallbackInfo ci) {
        GlStateTracker.cull(true);
    }

    @Inject(method = "_disableCull", at = @At("HEAD"))
    private static void scandium$disableCull(CallbackInfo ci) {
        GlStateTracker.cull(false);
    }

    @Inject(method = "_enablePolygonOffset", at = @At("HEAD"))
    private static void scandium$enableOffset(CallbackInfo ci) {
        GlStateTracker.polygonOffset(true);
    }

    @Inject(method = "_disablePolygonOffset", at = @At("HEAD"))
    private static void scandium$disableOffset(CallbackInfo ci) {
        GlStateTracker.polygonOffset(false);
    }

    @Inject(method = "_polygonOffset", at = @At("HEAD"))
    private static void scandium$polygonOffset(float factor, float units, CallbackInfo ci) {
        GlStateTracker.polygonOffset(factor, units);
    }

    @Inject(method = "_colorMask", at = @At("HEAD"))
    private static void scandium$colorMask(boolean r, boolean g, boolean b, boolean a, CallbackInfo ci) {
        GlStateTracker.colorMask(r, g, b, a);
    }

    @Inject(method = "_viewport", at = @At("HEAD"))
    private static void scandium$viewport(int x, int y, int w, int h, CallbackInfo ci) {
        GlStateTracker.viewport(x, y, w, h);
    }

    @Inject(method = "_enableScissorTest", at = @At("HEAD"))
    private static void scandium$enableScissor(CallbackInfo ci) {
        GlStateTracker.scissor(true);
    }

    @Inject(method = "_disableScissorTest", at = @At("HEAD"))
    private static void scandium$disableScissor(CallbackInfo ci) {
        GlStateTracker.scissor(false);
    }

    @Inject(method = "_scissorBox", at = @At("HEAD"))
    private static void scandium$scissorBox(int x, int y, int w, int h, CallbackInfo ci) {
        GlStateTracker.scissorBox(x, y, w, h);
    }

    @Inject(method = "_glBindFramebuffer", at = @At("HEAD"))
    private static void scandium$bindFramebuffer(int target, int framebuffer, CallbackInfo ci) {
        GlStateTracker.bindFramebuffer(target, framebuffer);
    }

    @Inject(method = "_clearColor", at = @At("HEAD"))
    private static void scandium$clearColor(float r, float g, float b, float a, CallbackInfo ci) {
        GlStateTracker.clearColor(r, g, b, a);
    }

    @Inject(method = "_clearDepth", at = @At("HEAD"))
    private static void scandium$clearDepth(double depth, CallbackInfo ci) {
        GlStateTracker.clearDepth(depth);
    }

    @Inject(method = "_clear", at = @At("HEAD"))
    private static void scandium$clear(int mask, boolean checkError, CallbackInfo ci) {
        ShadowRenderer.onClear(mask);
    }

    @Inject(method = "_activeTexture", at = @At("HEAD"))
    private static void scandium$activeTexture(int unit, CallbackInfo ci) {
        GlStateTracker.activeTexture(unit);
    }

    @Inject(method = "_bindTexture", at = @At("HEAD"))
    private static void scandium$bindTexture(int texture, CallbackInfo ci) {
        GlStateTracker.bindTexture(texture);
    }

    @Inject(method = "_pixelStore", at = @At("HEAD"))
    private static void scandium$pixelStore(int name, int value, CallbackInfo ci) {
        GlStateTracker.pixelStore(name, value);
    }

    @Inject(method = "_texParameter(III)V", at = @At("HEAD"))
    private static void scandium$texParameter(int target, int name, int value, CallbackInfo ci) {
        ShadowRenderer.onTexParameter(name, value);
    }

    @Inject(method = "_texImage2D", at = @At("HEAD"))
    private static void scandium$texImage(int target, int level, int internalFormat, int width, int height, int border,
                                          int format, int type, IntBuffer pixels, CallbackInfo ci) {
        ShadowRenderer.onTexImage(level, internalFormat, width, height);
    }

    @Inject(method = "_texSubImage2D", at = @At("HEAD"))
    private static void scandium$texSubImage(int target, int level, int x, int y, int width, int height, int format,
                                             int type, long pixels, CallbackInfo ci) {
        ShadowRenderer.onTexSubImage(level, x, y, width, height, format, type, pixels);
    }

    @Inject(method = "_deleteTexture", at = @At("HEAD"))
    private static void scandium$deleteTexture(int texture, CallbackInfo ci) {
        ShadowRenderer.onDeleteTexture(texture);
    }
}
