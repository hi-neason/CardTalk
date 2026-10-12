package com.cardtalk.assets;

import java.io.IOException;
import java.util.Set;

/** Immutable object bytes; discard is only for uploads that never published metadata. */
public interface AssetStore {
    void put(String id,byte[] bytes) throws IOException;
    byte[] read(String id) throws IOException;
    default void markPublished(String id) {}
    void discardUncommitted(String id) throws IOException;
    void cleanupUncommitted(Set<String> publishedIds) throws IOException;
}
