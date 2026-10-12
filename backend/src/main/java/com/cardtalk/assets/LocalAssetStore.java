package com.cardtalk.assets;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import java.io.*;
import java.nio.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;

/** Development only: single application instance owns this directory. */
@Component
@ConditionalOnProperty(name="cardtalk.asset-store-mode",havingValue="local",matchIfMissing=true)
public class LocalAssetStore implements AssetStore {
    private final Path root;
    private final int maxBytes;
    private final Set<String> pending=java.util.concurrent.ConcurrentHashMap.newKeySet();
    public LocalAssetStore(@Value("${cardtalk.asset-directory:./data/assets}") String directory,@Value("${cardtalk.asset-max-bytes:16777216}") int maxBytes) throws IOException {
        if(maxBytes<1||maxBytes>134217728) throw new IllegalArgumentException("Asset maximum must be 1..128 MiB");
        this.root=Path.of(directory).toAbsolutePath().resolve("objects");this.maxBytes=maxBytes;Files.createDirectories(root);
        if(Files.isSymbolicLink(root)) throw new IOException("Asset root must not be a symlink");
    }
    private Path file(String id) throws IOException { if(id==null||!id.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")) throw new IOException("Invalid asset identifier");return root.resolve(id+".blob"); }
    public void put(String id,byte[] bytes) throws IOException {
        if(bytes.length==0||bytes.length>maxBytes) throw new IOException("Invalid asset size");Path file=file(id);boolean created=false;
        try {
            try(var channel=FileChannel.open(file,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)) {
                created=true;var buffer=ByteBuffer.wrap(bytes);while(buffer.hasRemaining()) channel.write(buffer);channel.force(true);
            }
            pending.add(id);
        } catch(IOException failure) { if(created) try { Files.deleteIfExists(file); } catch(IOException cleanup) { failure.addSuppressed(cleanup); }throw failure; }
    }
    public byte[] read(String id) throws IOException {
        try(var channel=FileChannel.open(file(id),StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS)) {
            if(channel.size()==0||channel.size()>maxBytes) throw new IOException("Invalid stored asset size");
            var output=new ByteArrayOutputStream();var buffer=ByteBuffer.allocate(8192);
            while(channel.read(buffer)!=-1) { buffer.flip();if(output.size()+buffer.remaining()>maxBytes) throw new IOException("Stored asset exceeds limit");output.write(buffer.array(),0,buffer.remaining());buffer.clear(); }
            return output.toByteArray();
        }
    }
    public void markPublished(String id) { pending.remove(id); }
    public void discardUncommitted(String id) throws IOException { if(!pending.remove(id)) throw new IOException("Only this uploader can discard an uncommitted object");Files.deleteIfExists(file(id)); }
    public void cleanupUncommitted(Set<String> publishedIds) throws IOException {
        try(var files=Files.list(root)) {
            for(Path path:files.toList()) {
                String name=path.getFileName().toString();
                if(name.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}\\.blob")) {
                    String id=name.substring(0,name.length()-5);if(!publishedIds.contains(id)) Files.delete(path);
                }
            }
        }
    }
}
