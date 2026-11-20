package dev.scandium.ferrite.fastmap.immutable;

import com.google.common.collect.ScandiumFerriteImmutableCollectionAccess;
import com.google.common.collect.UnmodifiableIterator;
import dev.scandium.ferrite.ducks.FastMapStateHolder;
import dev.scandium.ferrite.fastmap.FastMap;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

public class FastMapValueSet extends ScandiumFerriteImmutableCollectionAccess<Comparable<?>> {
    private final FastMapStateHolder<?> viewedState;

    public FastMapValueSet(FastMapStateHolder<?> viewedState) {
        this.viewedState = viewedState;
    }

    @Override
    public UnmodifiableIterator<Comparable<?>> iterator() {
        return new FastMapEntryIterator<>(viewedState) {
            @Override
            protected Comparable<?> getEntry(int propertyIndex, FastMap<?> map, int stateIndex) {
                return map.getKey(propertyIndex).getValue(stateIndex);
            }
        };
    }

    @Override
    public int size() {
        return viewedState.getStateMap().numProperties();
    }

    @Override
    public boolean contains(@Nullable Object o) {
        for (var entry : this) {
            if (Objects.equals(entry, o)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean isPartialView() {
        return false;
    }
}
