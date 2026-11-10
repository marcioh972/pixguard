package br.com.pixguard.support;

import br.com.pixguard.client.BancoClient;
import br.com.pixguard.client.ConsultaCobranca;
import br.com.pixguard.client.PagamentoNoBanco;
import br.com.pixguard.exception.BancoIndisponivelException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** Banco programável pelo teste. Por padrão toda cobrança está ATIVA (não paga). Thread-safe. */
public final class BancoClientFalso implements BancoClient {

    private final Map<String, ConsultaCobranca> respostas = new ConcurrentHashMap<>();
    private final Set<String> indisponiveis = ConcurrentHashMap.newKeySet();
    private final List<String> consultas = new CopyOnWriteArrayList<>();

    public void pagou(String txid, PagamentoNoBanco pagamento) {
        respostas.put(txid, ConsultaCobranca.concluida(txid, pagamento));
    }

    public void indisponivel(String txid) {
        indisponiveis.add(txid);
    }

    public void voltou(String txid) {
        indisponiveis.remove(txid);
    }

    /** txids consultados, na ordem. */
    public List<String> consultas() {
        return List.copyOf(consultas);
    }

    public void limpar() {
        respostas.clear();
        indisponiveis.clear();
        consultas.clear();
    }

    @Override
    public ConsultaCobranca consultarCobranca(String txid) {
        consultas.add(txid);
        if (indisponiveis.contains(txid)) {
            throw new BancoIndisponivelException("falha simulada para " + txid);
        }
        return respostas.getOrDefault(txid, ConsultaCobranca.ativa(txid));
    }
}
