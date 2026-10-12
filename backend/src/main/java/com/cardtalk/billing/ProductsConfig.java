package com.cardtalk.billing;

import com.cardtalk.api.ApiException;
import com.fasterxml.jackson.databind.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.nio.file.*;
import java.util.*;

@Component
public class ProductsConfig {
    public enum Feature { profiles,scenarios,analytics }
    public record Product(String id,Feature feature,String title,long amountMinor,String currency,int durationDays) {}
    private final List<Product> products;
    public ProductsConfig(ObjectMapper mapper,@Value("${cardtalk.billing-products-file:}") String path){
        if(path.isBlank()){products=List.of();return;}
        try{
            if(Files.size(Path.of(path))>65536)throw new IllegalArgumentException();
            JsonNode root=mapper.readTree(Files.readAllBytes(Path.of(path)));if(!root.isObject()||root.size()!=1||!root.has("products")||!root.get("products").isArray()||root.get("products").size()>100)throw new IllegalArgumentException();
            var result=new ArrayList<Product>();Set<String> ids=new HashSet<>();
            for(JsonNode n:root.get("products")){
                if(!n.isObject()||n.size()!=6)throw new IllegalArgumentException();for(String key:List.of("id","feature","title","amountMinor","currency","durationDays"))if(!n.has(key))throw new IllegalArgumentException();
                if(!n.path("id").isTextual()||!n.get("id").asText().matches("[A-Za-z0-9_-]{1,100}")||!ids.add(n.get("id").asText())||!n.get("title").isTextual()||n.get("title").asText().isBlank()||n.get("title").asText().length()>100||!n.get("feature").isTextual()||!n.get("currency").isTextual()||!n.get("currency").asText().equals("CNY")||!n.get("amountMinor").isIntegralNumber()||!n.get("amountMinor").canConvertToLong()||n.get("amountMinor").longValue()<1||n.get("amountMinor").longValue()>100000000||!n.get("durationDays").isInt()||n.get("durationDays").intValue()<1||n.get("durationDays").intValue()>3660)throw new IllegalArgumentException();
                result.add(new Product(n.get("id").asText(),Feature.valueOf(n.get("feature").asText()),n.get("title").asText(),n.get("amountMinor").longValue(),"CNY",n.get("durationDays").intValue()));
            }products=List.copyOf(result);
        }catch(Exception e){throw new IllegalStateException("Billing product catalog is invalid; no fallback prices were created",e);}
    }
    public List<Product> products(){return products;}
    public Product product(String id){return products.stream().filter(p->p.id().equals(id)).findFirst().orElseThrow(()->new ApiException(400,"PRODUCT_UNAVAILABLE","此套餐不存在或已下架"));}
}
