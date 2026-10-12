package com.cardtalk.family;

import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import java.util.Set;

@Configuration(proxyBeanMethods=false)
public class FamilyRepositoryConfiguration {
    @Bean
    static BeanFactoryPostProcessor validateFamilyRepositoryMode(Environment environment) {
        return factory -> {
            String mode=environment.getProperty("cardtalk.family-repository-mode","json");
            if(!Set.of("json","postgres").contains(mode)) throw new IllegalStateException("CARDTALK_FAMILY_REPOSITORY_MODE must be exactly json or postgres; startup refused");
        };
    }
}
