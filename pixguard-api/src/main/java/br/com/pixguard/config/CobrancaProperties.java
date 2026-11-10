package br.com.pixguard.config;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.time.Duration;
import java.time.ZoneId;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Regras de expiração das cobranças. Validadas na subida: configuração errada derruba a aplicação
 * na partida em vez de gerar cobranças com prazo absurdo em produção.
 */
@Validated
@ConfigurationProperties(prefix = "pixguard.cobranca")
public record CobrancaProperties(
        @NotNull Duration expiracaoPadrao,
        @NotNull @PositiveOrZero Integer validadeAposVencimentoDias,
        @NotNull ZoneId fusoVencimento) {

    public CobrancaProperties {
        if (expiracaoPadrao != null && (expiracaoPadrao.isZero() || expiracaoPadrao.isNegative())) {
            throw new IllegalArgumentException("pixguard.cobranca.expiracao-padrao deve ser positiva");
        }
    }
}
