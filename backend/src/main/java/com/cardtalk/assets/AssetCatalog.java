package com.cardtalk.assets;

import com.cardtalk.api.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;
import java.io.*;
import java.time.Instant;
import java.util.*;
import java.security.*;

/** Coordinates immutable object bytes with independently committed metadata. */
@Repository
public class AssetCatalog {
    public record Metadata(String id,String familyId,String profileId,String mediaType,long bytes,int width,int height,String sha256,String createdAt) {
        public Metadata { createdAt=Instant.parse(createdAt).truncatedTo(java.time.temporal.ChronoUnit.MICROS).toString(); }
    }
    public record Snapshot(int version,List<Metadata> assets) {}
    private final AssetStore store;
    private final AssetMetadataRepository repository;
    public AssetCatalog(ObjectMapper mapper,AssetStore store,String directory) throws IOException { this(store,new JsonAssetMetadataRepository(mapper,directory)); }
    @Autowired
    public AssetCatalog(AssetStore store,AssetMetadataRepository repository) throws IOException {
        this.store=store;this.repository=repository;
        if(repository.exclusiveLocal()&&store instanceof LocalAssetStore) {
            var entries=repository.list();Set<String> ids=new HashSet<>();
            for(var entry:entries) { verify(entry,store.read(entry.id()));ids.add(entry.id());store.markPublished(entry.id()); }
            // Shared stores/catalogs never reclaim objects at startup: another uploader may be in flight.
            store.cleanupUncommitted(ids);
        }
    }
    static void validate(Metadata entry) throws IOException {
        try {
            if(!entry.id().equals(UUID.fromString(entry.id()).toString())||!entry.familyId().matches("[A-Za-z0-9_-]{1,100}")||!entry.profileId().matches("[A-Za-z0-9_-]{1,100}")||!Set.of("image/png","image/jpeg","image/webp").contains(entry.mediaType())||entry.bytes()<1||entry.width()<1||entry.height()<1||!entry.sha256().matches("[a-f0-9]{64}")) throw new IllegalArgumentException();Instant.parse(entry.createdAt());
        } catch(RuntimeException failure) { throw new IOException("Invalid asset metadata",failure); }
    }
    public static String sha256(byte[] bytes) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); } catch(NoSuchAlgorithmException e) { throw new IllegalStateException(e); } }
    private static void verify(Metadata metadata,byte[] bytes) throws IOException { if(metadata.bytes()!=bytes.length||!metadata.sha256().equals(sha256(bytes))) throw new IOException("Stored asset integrity check failed"); }
    public Metadata get(String id) {
        try { Metadata metadata=repository.find(id);if(metadata==null) throw new ApiException(404,"ASSET_NOT_FOUND","图片不存在或不可访问");return metadata; }
        catch(IOException failure) { throw new ApiException(503,"ASSET_STORAGE_UNAVAILABLE","图片元数据暂时无法读取",failure); }
    }
    public byte[] read(Metadata metadata) {
        if(!metadata.equals(get(metadata.id()))) throw new ApiException(404,"ASSET_NOT_FOUND","图片不存在或不可访问");
        try { byte[] bytes=store.read(metadata.id());verify(metadata,bytes);return bytes; }
        catch(IOException failure) { throw new ApiException(503,"ASSET_STORAGE_UNAVAILABLE","图片暂时无法读取",failure); }
    }
    public synchronized Metadata publish(Metadata metadata,byte[] bytes) {
        boolean uploaded=false;
        try {
            validate(metadata);if(repository.find(metadata.id())!=null) throw new ApiException(409,"ASSET_EXISTS","图片编号已存在");
            verify(metadata,bytes);store.put(metadata.id(),bytes);uploaded=true;
            // Read-back verifies object completeness before metadata becomes visible.
            verify(metadata,store.read(metadata.id()));persist(new Snapshot(1,List.of(metadata)));store.markPublished(metadata.id());return metadata;
        } catch(IOException failure) {
            if(uploaded) try {
                var published=repository.find(metadata.id());
                if(metadata.equals(published)) { verify(metadata,store.read(metadata.id()));store.markPublished(metadata.id());return metadata; }
                if(published==null&&!(failure instanceof AssetMetadataRepository.PublicationUncertain)) store.discardUncommitted(metadata.id());
            } catch(IOException cleanup) { failure.addSuppressed(cleanup); } finally { store.markPublished(metadata.id()); }
            throw new ApiException(503,"ASSET_STORAGE_UNAVAILABLE","图片未确认保存，请重试",failure);
        }
    }
    /** Retained failure-injection seam for the local catalog tests. */
    protected void persist(Snapshot snapshot) throws IOException { repository.publish(snapshot.assets().get(0)); }
}
