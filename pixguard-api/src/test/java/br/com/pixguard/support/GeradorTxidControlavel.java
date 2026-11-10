package br.com.pixguard.support;

import br.com.pixguard.service.GeradorTxid;
import br.com.pixguard.service.GeradorTxidAleatorio;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Deixa o teste escolher o próximo txid (necessário para simular "webhook antes da cobrança").
 * Sem txid enfileirado, gera aleatório como em produção.
 */
public final class GeradorTxidControlavel implements GeradorTxid {

    private final Queue<String> fila = new ConcurrentLinkedQueue<>();
    private final GeradorTxid real = new GeradorTxidAleatorio();

    public void proximo(String txid) {
        fila.add(txid);
    }

    public void limpar() {
        fila.clear();
    }

    @Override
    public String gerar() {
        String escolhido = fila.poll();
        return escolhido != null ? escolhido : real.gerar();
    }
}
