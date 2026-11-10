package br.com.pixguard.service;

import org.slf4j.MDC;

/**
 * Coloca txid (e, se houver, endToEndId) no MDC e GARANTE a limpeza ao sair (try-with-resources),
 * para um item nunca vazar contexto para o próximo na mesma thread. Se já havia um valor para a
 * chave (contextos aninhados, ex.: o job abre o contexto da cobrança e o PagamentoService abre o do
 * pagamento), ele é RESTAURADO ao fechar em vez de apagado. Ambos são identificadores técnicos,
 * não dado pessoal.
 */
final class ContextoLog implements AutoCloseable {

    private static final String CHAVE_TXID = "txid";
    private static final String CHAVE_END_TO_END_ID = "endToEndId";

    private final String txidAnterior;
    private final String endToEndIdAnterior;
    private final MDC.MDCCloseable txid;
    private final MDC.MDCCloseable endToEndId;

    private ContextoLog(String txid, String endToEndId) {
        this.txidAnterior = MDC.get(CHAVE_TXID);
        this.endToEndIdAnterior = MDC.get(CHAVE_END_TO_END_ID);
        this.txid = MDC.putCloseable(CHAVE_TXID, txid);
        this.endToEndId = endToEndId == null ? null : MDC.putCloseable(CHAVE_END_TO_END_ID, endToEndId);
    }

    static ContextoLog de(String txid, String endToEndId) {
        return new ContextoLog(txid, endToEndId);
    }

    static ContextoLog de(String txid) {
        return new ContextoLog(txid, null);
    }

    @Override
    public void close() {
        if (endToEndId != null) {
            endToEndId.close();
            restaurar(CHAVE_END_TO_END_ID, endToEndIdAnterior);
        }
        txid.close();
        restaurar(CHAVE_TXID, txidAnterior);
    }

    private static void restaurar(String chave, String valorAnterior) {
        if (valorAnterior != null) {
            MDC.put(chave, valorAnterior);
        }
    }
}
