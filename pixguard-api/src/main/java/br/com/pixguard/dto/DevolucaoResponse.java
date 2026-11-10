package br.com.pixguard.dto;

import br.com.pixguard.domain.Devolucao;
import br.com.pixguard.domain.StatusDevolucao;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record DevolucaoResponse(
        String idDevolucao,
        String endToEndId,
        String valor,
        StatusDevolucao status,
        String motivo,
        Instant solicitadaEm) {

    public static DevolucaoResponse de(Devolucao d) {
        return new DevolucaoResponse(
                d.getIdDevolucao(),
                d.getPagamento().getEndToEndId(),
                d.getValor().toPlainString(),
                d.getStatus(),
                d.getMotivo(),
                d.getSolicitadaEm());
    }
}
