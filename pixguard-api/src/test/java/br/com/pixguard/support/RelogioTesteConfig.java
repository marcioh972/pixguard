package br.com.pixguard.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

@TestConfiguration(proxyBeanMethods = false)
public class RelogioTesteConfig {

    /** @Primary: onde alguém pedir um Clock, recebe este em vez do relógio real da aplicação. */
    @Bean
    @Primary
    RelogioControlavel relogioControlavel() {
        return new RelogioControlavel(RelogioControlavel.INICIO_PADRAO);
    }
}
