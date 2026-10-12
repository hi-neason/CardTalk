package com.cardtalk.assets;

import org.springframework.context.annotation.*;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.core.env.Environment;
import java.util.Set;

@Configuration(proxyBeanMethods=false)
public class AssetStorageConfiguration {
    @Bean static BeanFactoryPostProcessor validateAssetStorageModes(Environment environment) {
        return factory->{
            String metadata=environment.getProperty("cardtalk.asset-metadata-mode","json"),objects=environment.getProperty("cardtalk.asset-store-mode","local");
            if(!Set.of("json","postgres").contains(metadata)||!Set.of("local","s3").contains(objects)) throw new IllegalStateException("Asset modes must be metadata json/postgres and store local/s3");
            if(metadata.equals("postgres")&&!environment.getProperty("cardtalk.family-repository-mode","json").equals("postgres")) throw new IllegalStateException("Postgres asset metadata requires Postgres family repository");
        };
    }
}
