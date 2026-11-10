package br.com.pixguard.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Único ponto onde o relógio real é criado. O resto do código recebe o Clock por injeção,
 * o que permite controlar o tempo nos testes (expiração, prazo de devolução).
 */
@Configuration(proxyBeanMethods = false)
public class ClockConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
