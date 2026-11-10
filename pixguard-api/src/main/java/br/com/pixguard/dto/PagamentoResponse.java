package br.com.pixguard.dto;

import br.com.pixguard.domain.Pagamento;
import br.com.pixguard.domain.StatusPagamento;
import java.time.Instant;

public record PagamentoResponse(
        String endToEndId,
        String txid,
        String valor,
        String valorDevolvido,
        StatusPagamento status,
        Instant pagoEm,
        Instant recebidoEm) {

    public static PagamentoResponse de(Pagamento p) {
        return new PagamentoResponse(
                p.getEndToEndId(),
                p.getTxid(),
                p.getValor().toPlainString(),
                p.getValorDevolvido().toPlainString(),
                p.getStatus(),
                p.getPagoEm(),
                p.getRecebidoEm());
    }
}
