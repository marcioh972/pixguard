package br.com.pixguard.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

@TestConfiguration(proxyBeanMethods = false)
public class TxidTesteConfig {

    @Bean
    @Primary
    GeradorTxidControlavel geradorTxidControlavel() {
        return new GeradorTxidControlavel();
    }
}
