package br.com.pixguard.config;

import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** @param prazo quanto tempo depois do pagamento (pago_em) uma devolução ainda é aceita */
@Validated
@ConfigurationProperties(prefix = "pixguard.devolucao")
public record DevolucaoProperties(@NotNull Duration prazo) {

    public DevolucaoProperties {
        if (prazo != null && (prazo.isZero() || prazo.isNegative())) {
            throw new IllegalArgumentException("pixguard.devolucao.prazo deve ser positivo");
        }
    }
}
