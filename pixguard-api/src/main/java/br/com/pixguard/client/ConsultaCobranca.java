package br.com.pixguard.client;

/** Estado da cobrança do ponto de vista do banco. */
public record ConsultaCobranca(String txid, boolean paga, PagamentoNoBanco pagamento) {

    public ConsultaCobranca {
        if (paga && pagamento == null) {
            throw new IllegalArgumentException("cobrança paga exige os dados do pagamento");
        }
    }

    public static ConsultaCobranca ativa(String txid) {
        return new ConsultaCobranca(txid, false, null);
    }

    public static ConsultaCobranca concluida(String txid, PagamentoNoBanco pagamento) {
        return new ConsultaCobranca(txid, true, pagamento);
    }
}
