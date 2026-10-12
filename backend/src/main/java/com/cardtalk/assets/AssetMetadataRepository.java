package com.cardtalk.assets;

import java.io.IOException;
import java.util.List;

/** Immutable metadata publication. A failed commit may be ambiguous across network boundaries. */
public interface AssetMetadataRepository {
    AssetCatalog.Metadata find(String id) throws IOException;
    void publish(AssetCatalog.Metadata metadata) throws IOException;
    List<AssetCatalog.Metadata> list() throws IOException;
    default boolean exclusiveLocal() { return false; }
    class PublicationUncertain extends IOException {
        public PublicationUncertain(Throwable cause) { super("Asset publication outcome is unknown; object retained",cause); }
    }
}
