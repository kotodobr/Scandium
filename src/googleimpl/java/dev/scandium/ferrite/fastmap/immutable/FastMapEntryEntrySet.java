package dev.scandium.ferrite.fastmap.immutable;

import com.google.common.collect.ScandiumFerriteEntrySetAccess;
import com.google.common.collect.UnmodifiableIterator;
import dev.scandium.ferrite.ducks.FastMapStateHolder;
import dev.scandium.ferrite.fastmap.FastMap;
import net.minecraft.world.level.block.state.properties.Property;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

public class FastMapEntryEntrySet extends ScandiumFerriteEntrySetAccess<Property<?>, Comparable<?>> {
    private final FastMapStateHolder<?> viewedState;

    public FastMapEntryEntrySet(FastMapStateHolder<?> viewedState) {
        this.viewedState = viewedState;
    }

    @Override
    @NotNull
    public UnmodifiableIterator<Map.Entry<Property<?>, Comparable<?>>> iterator() {
        return new FastMapEntryIterator<>(viewedState) {
            @Override
            protected Map.Entry<Property<?>, Comparable<?>> getEntry(
                    int propertyIndex, FastMap<?> map, int stateIndex
            ) {
                return map.getEntry(propertyIndex, stateIndex);
            }
        };
    }

    @Override
    public int size() {
        return viewedState.getStateMap().numProperties();
    }

    @Override
    public boolean contains(@Nullable Object object) {
        if (!(object instanceof Map.Entry<?, ?> entry)) {
            return false;
        }
        Comparable<?> valueInMap = viewedState.getStateMap().getValue(viewedState.getStateIndex(), entry.getKey());
        return valueInMap != null && valueInMap.equals(((Map.Entry<?, ?>) object).getValue());
    }

    @Override
    public boolean isPartialView() {
        return false;
    }
}
