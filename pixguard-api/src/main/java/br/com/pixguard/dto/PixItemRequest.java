package br.com.pixguard.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.Instant;

/** Um pagamento dentro do payload do webhook (formato do BCB). */
public record PixItemRequest(
        @NotNull(message = "endToEndId é obrigatório")
        @Pattern(regexp = PadroesValidacao.END_TO_END_ID,
                message = "endToEndId deve ter 32 caracteres alfanuméricos")
        String endToEndId,

        @NotNull(message = "txid é obrigatório")
        @Pattern(regexp = PadroesValidacao.TXID,
                message = "txid deve ter de 26 a 35 caracteres alfanuméricos")
        String txid,

        @NotNull(message = "valor é obrigatório")
        @Pattern(regexp = PadroesValidacao.VALOR_DECIMAL,
                message = "valor deve ser decimal com 2 casas e maior que zero (ex.: \"110.00\")")
        String valor,

        @NotNull(message = "horario é obrigatório")
        Instant horario) {
}
