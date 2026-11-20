package dev.scandium.vulkan;

import dev.scandium.Scandium;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;

import static dev.scandium.vulkan.VulkanException.check;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.EXTDebugUtils.*;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK11.vkEnumerateInstanceVersion;

/**
 * Vulkan instance, the chosen GPU, a logical device and one graphics+compute queue.
 * <p>
 * Device choice: the GPU must support Vulkan 1.2 and have a queue family with graphics and compute; discrete GPUs
 * are preferred over integrated ones (laptops with two GPUs expose both). Thread-safe to use from one thread at
 * a time; queue submissions must be externally synchronized.
 */
public final class VulkanContext implements AutoCloseable {
    public static final int REQUIRED_API = VK12.VK_API_VERSION_1_2;

    public final VkInstance instance;
    public final VkPhysicalDevice physicalDevice;
    public final VkDevice device;
    public final VkQueue queue;
    public final int queueFamily;
    public final String deviceName;
    public final String deviceType;
    public final int apiVersion;
    public final int driverVersion;
    public final long deviceLocalMemoryMb;
    public final long minUniformBufferOffsetAlignment;
    /** VK_KHR_swapchain enabled on the device: the backend can present to a window. */
    public final boolean swapchainSupported;
    /** One vkCmdDrawIndexedIndirect call may contain many draws (terrain regions, stage 8). */
    public final boolean multiDrawIndirect;

    /** Window-system integration extensions, enabled when the loader offers them (needed for a swapchain). */
    private static final String[] SURFACE_EXTENSIONS = {
            "VK_KHR_surface", "VK_KHR_win32_surface", "VK_KHR_xlib_surface", "VK_KHR_xcb_surface",
            "VK_KHR_wayland_surface", "VK_EXT_metal_surface"
    };
    private final VkPhysicalDeviceMemoryProperties memoryProperties;
    private final long debugMessenger;

    private VulkanContext(VkInstance instance, long debugMessenger, VkPhysicalDevice physicalDevice, int queueFamily) {
        this.instance = instance;
        this.debugMessenger = debugMessenger;
        this.physicalDevice = physicalDevice;
        this.queueFamily = queueFamily;
        this.memoryProperties = VkPhysicalDeviceMemoryProperties.malloc();
        vkGetPhysicalDeviceMemoryProperties(physicalDevice, this.memoryProperties);

        try (MemoryStack stack = stackPush()) {
            VkPhysicalDeviceProperties props = VkPhysicalDeviceProperties.malloc(stack);
            vkGetPhysicalDeviceProperties(physicalDevice, props);
            this.deviceName = props.deviceNameString();
            this.deviceType = typeName(props.deviceType());
            this.apiVersion = props.apiVersion();
            this.driverVersion = props.driverVersion();
            this.minUniformBufferOffsetAlignment = Math.max(16, props.limits().minUniformBufferOffsetAlignment());

            long local = 0;
            for (int i = 0; i < this.memoryProperties.memoryHeapCount(); i++) {
                VkMemoryHeap heap = this.memoryProperties.memoryHeaps(i);
                if ((heap.flags() & VK_MEMORY_HEAP_DEVICE_LOCAL_BIT) != 0) {
                    local += heap.size();
                }
            }
            this.deviceLocalMemoryMb = local >> 20;

            VkDeviceQueueCreateInfo.Buffer queueInfo = VkDeviceQueueCreateInfo.calloc(1, stack)
                    .sType$Default()
                    .queueFamilyIndex(queueFamily)
                    .pQueuePriorities(stack.floats(1.0f));
            VkPhysicalDeviceFeatures supportedFeatures = VkPhysicalDeviceFeatures.malloc(stack);
            vkGetPhysicalDeviceFeatures(physicalDevice, supportedFeatures);
            VkPhysicalDeviceFeatures features = VkPhysicalDeviceFeatures.calloc(stack);
            this.multiDrawIndirect = supportedFeatures.multiDrawIndirect();
            features.multiDrawIndirect(this.multiDrawIndirect);
            boolean swapchain = hasDeviceExtension(physicalDevice, "VK_KHR_swapchain");
            this.swapchainSupported = swapchain;
            VkDeviceCreateInfo deviceInfo = VkDeviceCreateInfo.calloc(stack)
                    .sType$Default()
                    .pQueueCreateInfos(queueInfo)
                    .pEnabledFeatures(features)
                    .ppEnabledExtensionNames(swapchain ? stack.pointers(stack.UTF8("VK_KHR_swapchain")) : null);
            PointerBuffer pDevice = stack.mallocPointer(1);
            check(vkCreateDevice(physicalDevice, deviceInfo, null, pDevice), "vkCreateDevice");
            this.device = new VkDevice(pDevice.get(0), physicalDevice, deviceInfo, REQUIRED_API);

            PointerBuffer pQueue = stack.mallocPointer(1);
            vkGetDeviceQueue(this.device, queueFamily, 0, pQueue);
            this.queue = new VkQueue(pQueue.get(0), this.device);
        }
    }

    public static VulkanContext create(boolean validation) {
        try (MemoryStack stack = stackPush()) {
            IntBuffer pVersion = stack.mallocInt(1);
            check(vkEnumerateInstanceVersion(pVersion), "vkEnumerateInstanceVersion");
            if (pVersion.get(0) < REQUIRED_API) {
                throw new VulkanException("Vulkan loader only supports " + versionString(pVersion.get(0))
                        + ", 1.2 is required");
            }

            List<String> layers = new ArrayList<>();
            List<String> extensions = new ArrayList<>();
            for (String surfaceExtension : SURFACE_EXTENSIONS) {
                if (hasInstanceExtension(surfaceExtension)) {
                    extensions.add(surfaceExtension);
                }
            }
            if (validation) {
                if (hasInstanceLayer("VK_LAYER_KHRONOS_validation")) {
                    layers.add("VK_LAYER_KHRONOS_validation");
                } else {
                    Scandium.LOGGER.warn("Vulkan validation requested, but VK_LAYER_KHRONOS_validation is not installed");
                }
                if (hasInstanceExtension(VK_EXT_DEBUG_UTILS_EXTENSION_NAME)) {
                    extensions.add(VK_EXT_DEBUG_UTILS_EXTENSION_NAME);
                }
            }

            VkApplicationInfo appInfo = VkApplicationInfo.calloc(stack)
                    .sType$Default()
                    .pApplicationName(stack.UTF8("Minecraft"))
                    .applicationVersion(VK_MAKE_API_VERSION(0, 1, 20, 4))
                    .pEngineName(stack.UTF8("Scandium"))
                    .engineVersion(VK_MAKE_API_VERSION(0, 1, 0, 0))
                    .apiVersion(REQUIRED_API);
            VkInstanceCreateInfo instanceInfo = VkInstanceCreateInfo.calloc(stack)
                    .sType$Default()
                    .pApplicationInfo(appInfo)
                    .ppEnabledLayerNames(toPointers(stack, layers))
                    .ppEnabledExtensionNames(toPointers(stack, extensions));
            PointerBuffer pInstance = stack.mallocPointer(1);
            check(vkCreateInstance(instanceInfo, null, pInstance), "vkCreateInstance");
            VkInstance instance = new VkInstance(pInstance.get(0), instanceInfo);

            try {
                long messenger = extensions.contains(VK_EXT_DEBUG_UTILS_EXTENSION_NAME)
                        ? createDebugMessenger(instance) : VK_NULL_HANDLE;
                DeviceChoice choice = pickDevice(instance);
                return new VulkanContext(instance, messenger, choice.device, choice.queueFamily);
            } catch (RuntimeException e) {
                vkDestroyInstance(instance, null);
                throw e;
            }
        }
    }

    private record DeviceChoice(VkPhysicalDevice device, int queueFamily, int score) {
    }

    private static DeviceChoice pickDevice(VkInstance instance) {
        try (MemoryStack stack = stackPush()) {
            IntBuffer count = stack.mallocInt(1);
            check(vkEnumeratePhysicalDevices(instance, count, null), "vkEnumeratePhysicalDevices");
            if (count.get(0) == 0) {
                throw new VulkanException("No Vulkan-capable GPU found");
            }
            PointerBuffer handles = stack.mallocPointer(count.get(0));
            check(vkEnumeratePhysicalDevices(instance, count, handles), "vkEnumeratePhysicalDevices");

            DeviceChoice best = null;
            for (int i = 0; i < handles.capacity(); i++) {
                VkPhysicalDevice device = new VkPhysicalDevice(handles.get(i), instance);
                VkPhysicalDeviceProperties props = VkPhysicalDeviceProperties.malloc(stack);
                vkGetPhysicalDeviceProperties(device, props);
                if (props.apiVersion() < REQUIRED_API) {
                    continue;
                }
                int family = findGraphicsComputeFamily(device, stack);
                if (family < 0) {
                    continue;
                }
                int score = switch (props.deviceType()) {
                    case VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU -> 1000;
                    case VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU -> 500;
                    case VK_PHYSICAL_DEVICE_TYPE_VIRTUAL_GPU -> 200;
                    default -> 10; // CPU implementations (lavapipe/SwiftShader): last resort
                };
                if (best == null || score > best.score) {
                    best = new DeviceChoice(device, family, score);
                }
            }
            if (best == null) {
                throw new VulkanException("No GPU with Vulkan 1.2 and a graphics+compute queue");
            }
            return best;
        }
    }

    private static int findGraphicsComputeFamily(VkPhysicalDevice device, MemoryStack stack) {
        IntBuffer count = stack.mallocInt(1);
        vkGetPhysicalDeviceQueueFamilyProperties(device, count, null);
        VkQueueFamilyProperties.Buffer families = VkQueueFamilyProperties.malloc(count.get(0), stack);
        vkGetPhysicalDeviceQueueFamilyProperties(device, count, families);
        int wanted = VK_QUEUE_GRAPHICS_BIT | VK_QUEUE_COMPUTE_BIT;
        for (int i = 0; i < families.capacity(); i++) {
            if ((families.get(i).queueFlags() & wanted) == wanted) {
                return i;
            }
        }
        return -1;
    }

    /** Whether {@code format} can be read as a vertex attribute (support for 3-component formats varies). */
    public boolean supportsVertexFormat(int format) {
        try (MemoryStack stack = stackPush()) {
            VkFormatProperties props = VkFormatProperties.malloc(stack);
            vkGetPhysicalDeviceFormatProperties(this.physicalDevice, format, props);
            return (props.bufferFeatures() & VK_FORMAT_FEATURE_VERTEX_BUFFER_BIT) != 0;
        }
    }

    public int memoryTypeFlags(int typeIndex) {
        return this.memoryProperties.memoryTypes(typeIndex).propertyFlags();
    }

    /** Index of a memory type allowed by {@code typeBits} that has all {@code required} property flags. */
    public int findMemoryType(int typeBits, int required) {
        for (int i = 0; i < this.memoryProperties.memoryTypeCount(); i++) {
            if ((typeBits & (1 << i)) != 0
                    && (this.memoryProperties.memoryTypes(i).propertyFlags() & required) == required) {
                return i;
            }
        }
        throw new VulkanException("No memory type with flags 0x" + Integer.toHexString(required));
    }

    public String describe() {
        return deviceName + " (" + deviceType + ", Vulkan " + versionString(apiVersion) + ", "
                + deviceLocalMemoryMb + " MB VRAM)";
    }

    public static String versionString(int version) {
        return VK_API_VERSION_MAJOR(version) + "." + VK_API_VERSION_MINOR(version) + "." + VK_API_VERSION_PATCH(version);
    }

    private static String typeName(int type) {
        return switch (type) {
            case VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU -> "discrete";
            case VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU -> "integrated";
            case VK_PHYSICAL_DEVICE_TYPE_VIRTUAL_GPU -> "virtual";
            case VK_PHYSICAL_DEVICE_TYPE_CPU -> "cpu";
            default -> "other";
        };
    }

    private static boolean hasInstanceLayer(String name) {
        try (MemoryStack stack = stackPush()) {
            IntBuffer count = stack.mallocInt(1);
            vkEnumerateInstanceLayerProperties(count, null);
            VkLayerProperties.Buffer props = VkLayerProperties.malloc(count.get(0), stack);
            vkEnumerateInstanceLayerProperties(count, props);
            for (int i = 0; i < props.capacity(); i++) {
                if (name.equals(props.get(i).layerNameString())) {
                    return true;
                }
            }
            return false;
        }
    }

    private static boolean hasInstanceExtension(String name) {
        try (MemoryStack stack = stackPush()) {
            IntBuffer count = stack.mallocInt(1);
            vkEnumerateInstanceExtensionProperties((String) null, count, null);
            VkExtensionProperties.Buffer props = VkExtensionProperties.malloc(count.get(0), stack);
            vkEnumerateInstanceExtensionProperties((String) null, count, props);
            for (int i = 0; i < props.capacity(); i++) {
                if (name.equals(props.get(i).extensionNameString())) {
                    return true;
                }
            }
            return false;
        }
    }

    private static boolean hasDeviceExtension(VkPhysicalDevice device, String name) {
        try (MemoryStack stack = stackPush()) {
            IntBuffer count = stack.mallocInt(1);
            vkEnumerateDeviceExtensionProperties(device, (String) null, count, null);
            VkExtensionProperties.Buffer props = VkExtensionProperties.malloc(count.get(0));
            try {
                vkEnumerateDeviceExtensionProperties(device, (String) null, count, props);
                for (int i = 0; i < props.capacity(); i++) {
                    if (name.equals(props.get(i).extensionNameString())) {
                        return true;
                    }
                }
                return false;
            } finally {
                props.free(); // heap, not stack: drivers list hundreds of extensions (see LwjglStack)
            }
        }
    }

    private static PointerBuffer toPointers(MemoryStack stack, List<String> names) {
        if (names.isEmpty()) {
            return null;
        }
        PointerBuffer buffer = stack.mallocPointer(names.size());
        for (String name : names) {
            buffer.put(stack.UTF8(name));
        }
        return buffer.flip();
    }

    private static long createDebugMessenger(VkInstance instance) {
        try (MemoryStack stack = stackPush()) {
            VkDebugUtilsMessengerCreateInfoEXT info = VkDebugUtilsMessengerCreateInfoEXT.calloc(stack)
                    .sType$Default()
                    .messageSeverity(VK_DEBUG_UTILS_MESSAGE_SEVERITY_WARNING_BIT_EXT
                            | VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT)
                    .messageType(VK_DEBUG_UTILS_MESSAGE_TYPE_GENERAL_BIT_EXT
                            | VK_DEBUG_UTILS_MESSAGE_TYPE_VALIDATION_BIT_EXT
                            | VK_DEBUG_UTILS_MESSAGE_TYPE_PERFORMANCE_BIT_EXT)
                    .pfnUserCallback((severity, types, pCallbackData, pUserData) -> {
                        VkDebugUtilsMessengerCallbackDataEXT data = VkDebugUtilsMessengerCallbackDataEXT.create(pCallbackData);
                        Scandium.LOGGER.warn("[Vulkan] {}", data.pMessageString());
                        return VK_FALSE;
                    });
            java.nio.LongBuffer pMessenger = stack.mallocLong(1);
            check(vkCreateDebugUtilsMessengerEXT(instance, info, null, pMessenger), "vkCreateDebugUtilsMessengerEXT");
            return pMessenger.get(0);
        }
    }

    @Override
    public void close() {
        vkDeviceWaitIdle(this.device);
        vkDestroyDevice(this.device, null);
        if (this.debugMessenger != VK_NULL_HANDLE) {
            vkDestroyDebugUtilsMessengerEXT(this.instance, this.debugMessenger, null);
        }
        vkDestroyInstance(this.instance, null);
        this.memoryProperties.free();
    }
}
