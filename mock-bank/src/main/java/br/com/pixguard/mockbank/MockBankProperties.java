package br.com.pixguard.mockbank;

import jakarta.validation.constraints.NotBlank;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/** @param webhook URL completa do webhook da API (ex.: http://localhost:8080/api/v1/webhooks/pix) */
@Validated
@ConfigurationProperties(prefix = "mockbank")
public record MockBankProperties(
        @NotBlank String webhook,
        @DefaultValue("2s") Duration timeoutConexao,
        @DefaultValue("5s") Duration timeoutLeitura) {
}
