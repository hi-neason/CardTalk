package com.cardtalk.assets;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;
import java.nio.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.io.*;
import java.util.*;

@Repository
@ConditionalOnProperty(name="cardtalk.asset-metadata-mode",havingValue="json",matchIfMissing=true)
public class JsonAssetMetadataRepository implements AssetMetadataRepository {
    private final ObjectMapper mapper;
    private final Path file;
    private final Map<String,AssetCatalog.Metadata> entries=new LinkedHashMap<>();
    public JsonAssetMetadataRepository(ObjectMapper mapper,@Value("${cardtalk.asset-directory:./data/assets}") String directory) throws IOException {
        this.mapper=mapper;file=Path.of(directory).toAbsolutePath().resolve("catalog.json");
        if(Files.exists(file)) {
            if(Files.isSymbolicLink(file)) throw new IOException("Asset catalog must not be a symlink");
            var snapshot=mapper.readValue(file.toFile(),AssetCatalog.Snapshot.class);
            try {
                if(snapshot.version()!=1) throw new IllegalArgumentException();
                for(var entry:snapshot.assets()) { AssetCatalog.validate(entry);if(entries.putIfAbsent(entry.id(),entry)!=null) throw new IllegalArgumentException(); }
            } catch(RuntimeException failure) { throw new IOException("Asset catalog is invalid; original data preserved",failure); }
        }
        if(Files.isDirectory(file.getParent())) try(var files=Files.list(file.getParent())) {
            for(Path path:files.toList()) if(path.getFileName().toString().matches("asset-catalog-[A-Za-z0-9_-]+\\.tmp")) Files.delete(path);
        }
    }
    public boolean exclusiveLocal() { return true; }
    public synchronized AssetCatalog.Metadata find(String id) { return entries.get(id); }
    public synchronized List<AssetCatalog.Metadata> list() { return List.copyOf(entries.values()); }
    public synchronized void publish(AssetCatalog.Metadata metadata) throws IOException {
        AssetCatalog.validate(metadata);if(entries.containsKey(metadata.id())) throw new IOException("Asset metadata is immutable");
        var next=new ArrayList<>(entries.values());next.add(metadata);Files.createDirectories(file.getParent());
        Path temp=Files.createTempFile(file.getParent(),"asset-catalog-",".tmp");boolean moved=false;
        try {
            try(var channel=FileChannel.open(temp,StandardOpenOption.WRITE)) { var buffer=ByteBuffer.wrap(mapper.writeValueAsBytes(new AssetCatalog.Snapshot(1,next)));while(buffer.hasRemaining()) channel.write(buffer);channel.force(true); }
            Files.move(temp,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);moved=true;
            entries.put(metadata.id(),metadata);
        } finally { if(!moved) Files.deleteIfExists(temp); }
    }
}
