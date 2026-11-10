package br.com.pixguard.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

@TestConfiguration(proxyBeanMethods = false)
public class BancoTesteConfig {

    /** @Primary: o job consulta este banco falso em vez do cliente HTTP real. */
    @Bean
    @Primary
    BancoClientFalso bancoClientFalso() {
        return new BancoClientFalso();
    }
}
