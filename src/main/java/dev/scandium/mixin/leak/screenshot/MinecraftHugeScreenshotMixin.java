package dev.scandium.mixin.leak.screenshot;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.mojang.blaze3d.platform.GlUtil;
import dev.scandium.Scandium;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Leaks when a huge (tiled) screenshot fails half-way.
 * <p>
 * {@code grabHugeScreenshot} allocates an off-heap tile buffer and opens a file stream for the output image, but
 * only releases them on success. On failure the native buffer is lost forever and the file handle stays open
 * (on Windows the half-written file can't even be deleted until the game exits). Both are now released, and the
 * broken partial file is removed.
 * <p>
 * The method only ever runs on the render thread and isn't re-entrant, so plain fields are enough to remember
 * the resources between the injection points.
 */
@Mixin(Minecraft.class)
public abstract class MinecraftHugeScreenshotMixin {
    @Unique
    private ByteBuffer scandium$tileBuffer;
    @Unique
    private Screenshot scandium$screenshot;

    @ModifyExpressionValue(
            method = "grabHugeScreenshot",
            at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/platform/GlUtil;allocateMemory(I)Ljava/nio/ByteBuffer;")
    )
    private ByteBuffer scandium$rememberBuffer(ByteBuffer buffer) {
        this.scandium$tileBuffer = buffer;
        return buffer;
    }

    @ModifyExpressionValue(method = "grabHugeScreenshot", at = @At(value = "NEW", target = "net/minecraft/client/Screenshot"))
    private Screenshot scandium$rememberScreenshot(Screenshot screenshot) {
        this.scandium$screenshot = screenshot;
        return screenshot;
    }

    @Inject(
            method = "grabHugeScreenshot",
            at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/platform/GlUtil;freeMemory(Ljava/nio/Buffer;)V", shift = At.Shift.AFTER)
    )
    private void scandium$forgetOnSuccess(File dir, int tileW, int tileH, int w, int h, CallbackInfoReturnable<Component> cir) {
        // Vanilla released everything itself.
        this.scandium$tileBuffer = null;
        this.scandium$screenshot = null;
    }

    @Inject(method = "grabHugeScreenshot", at = @At(value = "CONSTANT", args = "stringValue=screenshot.failure"))
    private void scandium$releaseOnFailure(File dir, int tileW, int tileH, int w, int h, CallbackInfoReturnable<Component> cir) {
        ByteBuffer buffer = this.scandium$tileBuffer;
        Screenshot screenshot = this.scandium$screenshot;
        this.scandium$tileBuffer = null;
        this.scandium$screenshot = null;
        if (buffer != null) {
            GlUtil.freeMemory(buffer);
        }
        if (screenshot != null) {
            try {
                File partial = screenshot.close();
                if (partial != null && partial.exists() && !partial.delete()) {
                    partial.deleteOnExit();
                }
            } catch (IOException e) {
                Scandium.LOGGER.debug("Failed to close broken huge screenshot", e);
            }
        }
    }
}
