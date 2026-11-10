package br.com.pixguard.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record RegistrarDevolucaoRequest(
        @NotNull(message = "valor é obrigatório")
        @Pattern(regexp = PadroesValidacao.VALOR_DECIMAL,
                message = "valor deve ser decimal com 2 casas e maior que zero (ex.: \"60.00\")")
        String valor,

        @Size(max = PadroesValidacao.TAMANHO_MAXIMO_MOTIVO, message = "motivo deve ter no máximo 140 caracteres")
        String motivo) {
}
