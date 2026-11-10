package br.com.pixguard.dto;

import br.com.pixguard.domain.Cobranca;
import br.com.pixguard.domain.StatusCobranca;
import br.com.pixguard.domain.TipoCobranca;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.time.LocalDate;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record CobrancaResponse(
        String txid,
        TipoCobranca tipo,
        String valor,
        StatusCobranca status,
        LocalDate dataVencimento,
        Instant criadaEm,
        Instant expiraEm) {

    public static CobrancaResponse de(Cobranca c) {
        return new CobrancaResponse(
                c.getTxid(),
                c.getTipo(),
                c.getValor().toPlainString(),
                c.getStatus(),
                c.getDataVencimento(),
                c.getCriadaEm(),
                c.getExpiraEm());
    }
}
