package dev.scandium.ferrite.mixin.fastmap;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Table;
import dev.scandium.ferrite.ducks.FastMapStateHolder;
import dev.scandium.ferrite.fastmap.FastMap;
import dev.scandium.ferrite.impl.StateHolderImpl;
import net.minecraft.world.level.block.state.StateHolder;
import net.minecraft.world.level.block.state.properties.Property;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.Map;

@Mixin(StateHolder.class)
public abstract class FastMapStateHolderMixin<O, S> implements FastMapStateHolder<S> {
    @Mutable
    @Shadow
    @Final
    private ImmutableMap<Property<?>, Comparable<?>> values;
    @Shadow
    private Table<Property<?>, Comparable<?>, S> neighbours;

    private int scandium_globalTableIndex;
    private FastMap<S> scandium_globalTable;

    @Redirect(
            method = {"setValue", "trySetValue"},
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/google/common/collect/Table;get(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;",
                    remap = false
            )
    )
    public Object getNeighborFromFastMap(Table<?, ?, ?> ignore, Object rowKey, Object columnKey) {
        return this.scandium_globalTable.withUnsafe(
                this.scandium_globalTableIndex,
                (Property<?>) rowKey,
                columnKey
        );
    }

    /**
     * @reason This Mixin completely replaces the data structures initialized by this method, as the original ones waste
     * a lot of memory
     * @author malte0811
     */
    @Overwrite
    public void populateNeighbours(Map<Map<Property<?>, Comparable<?>>, S> states) {
        StateHolderImpl.populateNeighbors(states, this);
    }

    @Override
    public FastMap<S> getStateMap() {
        return scandium_globalTable;
    }

    @Override
    public int getStateIndex() {
        return scandium_globalTableIndex;
    }

    @Override
    public ImmutableMap<Property<?>, Comparable<?>> getVanillaPropertyMap() {
        return values;
    }

    @Override
    public void replacePropertyMap(ImmutableMap<Property<?>, Comparable<?>> newMap) {
        values = newMap;
    }

    @Override
    public void setStateMap(FastMap<S> newValue) {
        scandium_globalTable = newValue;
    }

    @Override
    public void setStateIndex(int newValue) {
        scandium_globalTableIndex = newValue;
    }

    @Override
    public void setNeighborTable(Table<Property<?>, Comparable<?>, S> table) {
        neighbours = table;
    }

    @Override
    public Table<Property<?>, Comparable<?>, S> getNeighborTable() {
        return neighbours;
    }
}
