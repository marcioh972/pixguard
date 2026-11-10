package br.com.pixguard.config;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * @param maxRetentativas quantas vezes REPETIR um item depois da primeira tentativa, quando ele perde uma
 *                        disputa de concorrência (0 desliga o retry)
 * @param backoffBase     espera base entre tentativas; a espera real cresce com o número da tentativa e
 *                        ganha um pequeno sorteio (jitter) para threads em conflito não repetirem em compasso
 */
@Validated
@ConfigurationProperties(prefix = "pixguard.webhook")
public record WebhookProperties(
        @NotNull @PositiveOrZero Integer maxRetentativas,
        @NotNull Duration backoffBase) {

    public WebhookProperties {
        if (backoffBase != null && backoffBase.isNegative()) {
            throw new IllegalArgumentException("pixguard.webhook.backoff-base não pode ser negativo");
        }
    }
}
