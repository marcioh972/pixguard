package br.com.pixguard.client;

import br.com.pixguard.exception.BancoIndisponivelException;

/** Contrato com o banco. Interface para o job não depender de HTTP e os testes poderem simular o banco. */
public interface BancoClient {

    /**
     * @throws BancoIndisponivelException em QUALQUER falha (rede, timeout, 4xx/5xx, resposta inválida).
     *         O chamador nunca deve alterar estado com base em uma consulta que falhou.
     */
    ConsultaCobranca consultarCobranca(String txid);
}
