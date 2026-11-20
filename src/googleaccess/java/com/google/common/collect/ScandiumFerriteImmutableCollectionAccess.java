package com.google.common.collect;

/**
 * Same as {@link ScandiumFerriteImmutableMapAccess}
 */
public abstract class ScandiumFerriteImmutableCollectionAccess<T> extends ImmutableCollection<T> {
    public abstract boolean isPartialView();
}
