package dev.scandium.vulkan.present;

import dev.scandium.Scandium;
import dev.scandium.config.ScandiumConfig;
import dev.scandium.vulkan.VulkanBackend;
import dev.scandium.vulkan.VulkanRuntime;
import dev.scandium.vulkan.shadow.ShadowRenderer;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWVulkan;
import org.lwjgl.system.MemoryStack;

import java.nio.IntBuffer;
import java.nio.LongBuffer;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.KHRSurface.vkDestroySurfaceKHR;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Stage 5: the game window is shown by Vulkan.
 * <p>
 * The window is created without an OpenGL context (GLFW_NO_API) and gets a Vulkan surface and swapchain. OpenGL
 * keeps running in a second, invisible window with its own context: the game still issues every GL call and
 * renders into its framebuffer objects, and the shadow renderer replays those draws with Vulkan. At the end of
 * each frame ({@code glfwSwapBuffers}) the Vulkan frame is copied into the swapchain and presented.
 * <p>
 * Safety nets:
 * <ul>
 *     <li>decided before the window exists; any problem there (no runtime, no swapchain support, surface
 *     creation failing) leaves the normal GL window</li>
 *     <li>while the Vulkan frame can't be complete (first resource load, before shaders are translated) or after
 *     the shadow renderer failed, the GL picture is read back and presented through the swapchain instead, so
 *     the screen never goes black</li>
 * </ul>
 */
public final class VulkanPresent {
    public enum Source {
        /** The Vulkan shadow frame. */
        VULKAN,
        /** GL main target read back (glReadPixels) and copied in: fallback and A/B comparison. */
        OPENGL
    }

    private static boolean takeover;
    private static long visibleWindow;
    private static long glWindow;
    private static long surface;
    private static Swapchain swapchain;
    private static boolean recreate;
    private static boolean vsync = true;
    private static volatile Source requestedSource = Source.VULKAN;

    private VulkanPresent() {
    }

    public static boolean isTakeover() {
        return takeover;
    }

    public static long glWindow() {
        return glWindow;
    }

    public static Source requestedSource() {
        return requestedSource;
    }

    public static void setRequestedSource(Source source) {
        requestedSource = source;
    }

    /**
     * Window constructor, instead of {@code glfwCreateWindow}: with GL hints already set by vanilla. Returns the
     * handle the game will use as "its" window.
     */
    public static long createWindows(int width, int height, CharSequence title, long monitor, long share) {
        ScandiumConfig cfg = ScandiumConfig.get();
        VulkanRuntime rt = null;
        if (cfg.vulkanBackend && cfg.vulkanPresent) {
            VulkanBackend.initAsync();
            rt = VulkanBackend.awaitRuntime();
            if (rt != null && !ShadowRenderer.isActive()) {
                ShadowRenderer.activate(rt);
            }
        }
        if (rt == null || !rt.context.swapchainSupported || !ShadowRenderer.isActive() || !GLFWVulkan.glfwVulkanSupported()) {
            if (cfg.vulkanBackend && cfg.vulkanPresent) {
                Scandium.LOGGER.warn("Vulkan presentation unavailable (runtime: {}, swapchain: {}), using the OpenGL window",
                        rt != null, rt != null && rt.context.swapchainSupported);
            }
            return GLFW.glfwCreateWindow(width, height, title, monitor, share);
        }

        // Invisible GL window: same GL hints vanilla just set, never shown, only its context is used.
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
        long gl = GLFW.glfwCreateWindow(64, 64, "Scandium OpenGL", 0L, 0L);
        if (gl == 0L) {
            Scandium.LOGGER.warn("Could not create the hidden OpenGL window, using the OpenGL window");
            GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_TRUE);
            return GLFW.glfwCreateWindow(width, height, title, monitor, share);
        }
        GLFW.glfwDefaultWindowHints();
        GLFW.glfwWindowHint(GLFW.GLFW_CLIENT_API, GLFW.GLFW_NO_API);
        long window = GLFW.glfwCreateWindow(width, height, title, monitor, 0L);
        if (window == 0L) {
            Scandium.LOGGER.warn("Could not create a Vulkan window, using the OpenGL window");
            GLFW.glfwDestroyWindow(gl);
            GLFW.glfwDefaultWindowHints();
            return GLFW.glfwCreateWindow(width, height, title, monitor, share);
        }
        try (MemoryStack stack = stackPush()) {
            LongBuffer pSurface = stack.mallocLong(1);
            int result = GLFWVulkan.glfwCreateWindowSurface(rt.context.instance, window, null, pSurface);
            if (result != VK_SUCCESS) {
                throw new IllegalStateException("glfwCreateWindowSurface: " + result);
            }
            surface = pSurface.get(0);
            IntBuffer w = stack.mallocInt(1);
            IntBuffer h = stack.mallocInt(1);
            GLFW.glfwGetFramebufferSize(window, w, h);
            swapchain = Swapchain.create(rt.context, surface, Math.max(1, w.get(0)), Math.max(1, h.get(0)), vsync,
                    VK_NULL_HANDLE);
        } catch (RuntimeException e) {
            Scandium.LOGGER.warn("Vulkan swapchain could not be created, using the OpenGL window", e);
            if (surface != 0L) {
                vkDestroySurfaceKHR(rt.context.instance, surface, null);
                surface = 0L;
            }
            GLFW.glfwDestroyWindow(window);
            GLFW.glfwDestroyWindow(gl);
            GLFW.glfwDefaultWindowHints();
            return GLFW.glfwCreateWindow(width, height, title, monitor, share);
        }
        visibleWindow = window;
        glWindow = gl;
        takeover = true;
        Scandium.LOGGER.info("Vulkan presentation: the window is shown by Vulkan ({}x{}, {}), OpenGL runs in a hidden "
                + "context", swapchain.width, swapchain.height, swapchain.isBgra() ? "BGRA8" : "RGBA8");
        return window;
    }

    /** glfwMakeContextCurrent(window) in the Window constructor: the GL context lives in the hidden window. */
    public static long contextWindow(long requested) {
        return takeover && requested == visibleWindow ? glWindow : requested;
    }

    public static void setVsync(boolean enabled) {
        if (vsync != enabled) {
            vsync = enabled;
            recreate = true;
        }
    }

    public static void invalidate() {
        recreate = true;
    }

    /** The swapchain for this frame, recreated if the window size or VSync changed; null while minimized. */
    public static Swapchain swapchain(VulkanRuntime rt) {
        try (MemoryStack stack = stackPush()) {
            IntBuffer w = stack.mallocInt(1);
            IntBuffer h = stack.mallocInt(1);
            GLFW.glfwGetFramebufferSize(visibleWindow, w, h);
            if (w.get(0) == 0 || h.get(0) == 0) {
                return null; // minimized
            }
            if (recreate || swapchain == null || swapchain.width != w.get(0) || swapchain.height != h.get(0)) {
                ShadowRenderer.waitIdleForPresent();
                Swapchain old = swapchain;
                swapchain = Swapchain.create(rt.context, surface, w.get(0), h.get(0), vsync,
                        old == null ? VK_NULL_HANDLE : old.handle);
                if (old != null) {
                    old.close();
                }
                recreate = false;
            }
            return swapchain;
        }
    }

    /** Window.close: swapchain and surface go before the windows. */
    public static void shutdown(VulkanRuntime rt) {
        if (!takeover) {
            return;
        }
        try {
            ShadowRenderer.waitIdleForPresent();
            if (swapchain != null) {
                swapchain.close();
                swapchain = null;
            }
            if (surface != 0L && rt != null) {
                vkDestroySurfaceKHR(rt.context.instance, surface, null);
                surface = 0L;
            }
        } catch (RuntimeException e) {
            Scandium.LOGGER.debug("Vulkan presentation shutdown", e);
        }
        if (glWindow != 0L) {
            GLFW.glfwDestroyWindow(glWindow);
            glWindow = 0L;
        }
        takeover = false;
    }
}
