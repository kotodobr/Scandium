package dev.scandium.vulkan;

import dev.scandium.Scandium;
import org.lwjgl.system.MemoryStack;

import java.lang.reflect.Field;

/**
 * Gives the current thread a larger LWJGL {@link MemoryStack}.
 * <p>
 * LWJGL's thread-local stack is 64 KB by default, and its size can only be configured before the class is
 * initialized - Minecraft does that long before any mod runs. {@code new VkInstance(...)} allocates the extension
 * list of every GPU on that stack: a driver with ~250 device extensions needs ~65 KB per GPU, so machines with
 * a modern driver (or two GPUs) overflow it ("Out of stack space"). The stack is stored in a ThreadLocal, so a
 * bigger one can be installed for Scandium's own threads without affecting the rest of the game.
 */
final class LwjglStack {
    private LwjglStack() {
    }

    /** Must be called on a thread that hasn't used its MemoryStack yet (or has nothing pushed). */
    @SuppressWarnings("unchecked")
    static void enlargeForCurrentThread(int bytes) {
        try {
            Field field = MemoryStack.class.getDeclaredField("TLS");
            field.setAccessible(true);
            ThreadLocal<MemoryStack> tls = (ThreadLocal<MemoryStack>) field.get(null);
            tls.set(MemoryStack.create(bytes));
        } catch (ReflectiveOperationException | RuntimeException e) {
            Scandium.LOGGER.warn("Could not enlarge the LWJGL stack for {}: {}", Thread.currentThread().getName(),
                    e.toString());
        }
    }
}
