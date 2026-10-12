package com.cardtalk.billing;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class ProductsConfigTest {
    @TempDir Path dir;
    @Test void missingCatalogHasNoInventedPricesAndMalformedCatalogFailsClosed()throws Exception{
        var mapper=new ObjectMapper();assertTrue(new ProductsConfig(mapper,"").products().isEmpty());Path file=dir.resolve("catalog.json");
        for(String raw:new String[]{"{}","{\"products\":[{}]}","{\"products\":[],\"password\":\"unrelated\"}","{\"products\":[{\"id\":\"x\",\"feature\":\"all\",\"title\":\"invalid\",\"amountMinor\":-1,\"currency\":\"CNY\",\"durationDays\":1}]}","not-json"}){Files.writeString(file,raw);assertThrows(IllegalStateException.class,()->new ProductsConfig(mapper,file.toString()));}
    }
}
