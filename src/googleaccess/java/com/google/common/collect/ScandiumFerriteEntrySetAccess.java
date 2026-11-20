package com.google.common.collect;

import java.util.Map;

/**
 * Same as {@link ScandiumFerriteImmutableMapAccess}
 */
public abstract class ScandiumFerriteEntrySetAccess<K, V> extends ImmutableSet<Map.Entry<K, V>> {

    public ScandiumFerriteEntrySetAccess() {}

    @Override
    public abstract boolean isPartialView();
}
