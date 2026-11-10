package br.com.pixguard.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Conexão com o banco (PSP) consultado pelo job. Os timeouts impedem que um banco lento trave o job. */
@Validated
@ConfigurationProperties(prefix = "pixguard.banco")
public record BancoProperties(
        @NotBlank String url,
        @NotNull Duration timeoutConexao,
        @NotNull Duration timeoutLeitura) {
}
