package br.com.pixguard.dto;

import br.com.pixguard.domain.TipoCobranca;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.LocalDate;

public record CriarCobrancaRequest(
        @NotNull(message = "tipo é obrigatório")
        TipoCobranca tipo,

        @NotNull(message = "valor é obrigatório")
        @Pattern(regexp = PadroesValidacao.VALOR_DECIMAL,
                message = "valor deve ser decimal com 2 casas e maior que zero (ex.: \"110.00\")")
        String valor,

        LocalDate dataVencimento) {

    @JsonIgnore
    @AssertTrue(message = "dataVencimento é obrigatória para COM_VENCIMENTO e proibida para IMEDIATA")
    public boolean isVencimentoCoerente() {
        if (tipo == null) {
            return true; // já reportado por @NotNull
        }
        return (tipo == TipoCobranca.COM_VENCIMENTO) == (dataVencimento != null);
    }
}
