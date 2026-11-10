package br.com.pixguard.mockbank;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record PagarRequest(
        @NotNull @Pattern(regexp = "^[a-zA-Z0-9]{26,35}$", message = "txid deve ter de 26 a 35 caracteres alfanuméricos")
        String txid,

        @NotNull @Pattern(regexp = "^(?!0\\.00$)(0|[1-9]\\d{0,12})\\.\\d{2}$",
                message = "valor deve ser decimal com 2 casas e maior que zero (ex.: \"110.00\")")
        String valor,

        /** Opcional: se ausente, o banco gera um. Em FORA_DE_ORDEM vale para o pagamento mais antigo. */
        @Pattern(regexp = "^[a-zA-Z0-9]{32}$", message = "endToEndId deve ter 32 caracteres alfanuméricos")
        String endToEndId,

        @NotNull ModoPagamento modo,

        /** Só para ATRASADO. Padrão: 5000. */
        @Min(0) @Max(86_400_000) Long atrasoMs,

        /** Só para DUPLICADO e FORA_DE_ORDEM. Padrão: 2. */
        @Min(1) @Max(10) Integer copias) {
}
