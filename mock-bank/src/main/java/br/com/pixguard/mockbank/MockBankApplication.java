package br.com.pixguard.mockbank;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Banco simulado. Só o contrato HTTP o liga ao pixguard-api: ele NÃO depende de nenhuma classe da API.
 * Estado em memória: reiniciar o processo zera tudo.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class MockBankApplication {

    public static void main(String[] args) {
        SpringApplication.run(MockBankApplication.class, args);
    }
}
