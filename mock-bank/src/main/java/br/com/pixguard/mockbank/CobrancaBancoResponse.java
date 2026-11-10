package br.com.pixguard.mockbank;

import com.fasterxml.jackson.annotation.JsonInclude;

/** Cobrança do ponto de vista do banco: ATIVA (sem pagamento) ou CONCLUIDA (com o pix que a pagou). */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CobrancaBancoResponse(String txid, String status, PixInfo pix) {

    public record PixInfo(String endToEndId, String valor, String horario) {
    }

    static CobrancaBancoResponse ativa(String txid) {
        return new CobrancaBancoResponse(txid, "ATIVA", null);
    }

    static CobrancaBancoResponse concluida(PagamentoMock p) {
        return new CobrancaBancoResponse(p.txid(), "CONCLUIDA",
                new PixInfo(p.endToEndId(), p.valor(), p.horario().toString()));
    }
}
