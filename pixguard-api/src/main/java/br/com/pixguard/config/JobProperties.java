package br.com.pixguard.config;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * @param habilitado          liga/desliga o agendamento (os testes chamam o serviço direto)
 * @param intervalo           pausa entre o fim de um ciclo e o início do próximo
 * @param tamanhoLote         máximo de itens por etapa em cada ciclo
 * @param toleranciaExpiracao folga após expira_em antes de consultar o banco e expirar a cobrança
 */
@Validated
@ConfigurationProperties(prefix = "pixguard.job")
public record JobProperties(
        @NotNull Boolean habilitado,
        @NotNull Duration intervalo,
        @NotNull @Positive Integer tamanhoLote,
        @NotNull Duration toleranciaExpiracao) {

    public JobProperties {
        if (intervalo != null && (intervalo.isZero() || intervalo.isNegative())) {
            throw new IllegalArgumentException("pixguard.job.intervalo deve ser positivo");
        }
        if (toleranciaExpiracao != null && toleranciaExpiracao.isNegative()) {
            throw new IllegalArgumentException("pixguard.job.tolerancia-expiracao não pode ser negativa");
        }
    }
}
