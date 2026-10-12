package com.cardtalk.assets;

import com.cardtalk.api.ApiException;
import com.cardtalk.family.FamilyService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.UUID;

@Service
public class AssetService {
    private final FamilyService families;
    private final AssetCatalog catalog;
    private final int maxBytes,maxDimension;
    private final long maxPixels;
    public record Content(AssetCatalog.Metadata metadata,byte[] bytes) {}
    public AssetService(FamilyService families,AssetCatalog catalog,@Value("${cardtalk.asset-max-bytes:16777216}") int maxBytes,@Value("${cardtalk.asset-max-dimension:16384}") int maxDimension,@Value("${cardtalk.asset-max-pixels:40000000}") long maxPixels) {
        if(maxBytes<1||maxBytes>134217728||maxDimension<1||maxDimension>65535||maxPixels<1||maxPixels>100000000) throw new IllegalArgumentException("Invalid asset upload limits");
        this.families=families;this.catalog=catalog;this.maxBytes=maxBytes;this.maxDimension=maxDimension;this.maxPixels=maxPixels;
    }
    private static void identifier(String id) { if(id==null||!id.matches("[A-Za-z0-9_-]{1,100}")) throw new ApiException(400,"INVALID_REQUEST","图片所属家庭或使用人编号无效"); }
    public AssetCatalog.Metadata upload(FamilyService.Identity account,String familyId,String profileId,String mediaType,byte[] bytes) {
        identifier(familyId);identifier(profileId);families.authorizeProfile(account,familyId,profileId,true);
        var dimensions=RasterValidator.validate(bytes,mediaType,maxBytes,maxDimension,maxPixels);
        var metadata=new AssetCatalog.Metadata(UUID.randomUUID().toString(),familyId,profileId,mediaType,bytes.length,dimensions.width(),dimensions.height(),AssetCatalog.sha256(bytes),Instant.now().toString());
        // Recheck after potentially slow decoding so revoked memberships cannot publish new uploads.
        families.authorizeProfile(account,familyId,profileId,true);return catalog.publish(metadata,bytes);
    }
    public AssetCatalog.Metadata metadata(FamilyService.Identity account,String familyId,String assetId) {
        identifier(familyId);identifier(assetId);
        var metadata=catalog.get(assetId);if(!metadata.familyId().equals(familyId)) throw new ApiException(404,"ASSET_NOT_FOUND","图片不存在或不可访问");
        families.authorizeProfile(account,familyId,metadata.profileId(),false);return metadata;
    }
    public Content download(FamilyService.Identity account,String familyId,String assetId) {
        var metadata=metadata(account,familyId,assetId);return new Content(metadata,catalog.read(metadata));
    }
}
