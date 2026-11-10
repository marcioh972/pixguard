package br.com.pixguard.repository;

import br.com.pixguard.domain.Pagamento;
import br.com.pixguard.domain.StatusPagamento;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PagamentoRepository extends JpaRepository<Pagamento, Long> {

    Optional<Pagamento> findByEndToEndId(String endToEndId);

    /** Mais antigo primeiro (pelo horário do banco), desempate pelo id: quem pagou antes é o pagamento válido. */
    List<Pagamento> findByTxidAndStatusOrderByPagoEmAscIdAsc(String txid, StatusPagamento status);

    /**
     * Rede de segurança do job: pagamentos ainda AGUARDANDO_COBRANCA cuja cobrança já existe (a corrida em que
     * webhook e criação aconteceram ao mesmo tempo e nenhum viu o outro). O predicado "cobranca is null"
     * repete a condição do índice parcial idx_pagamento_aguardando_txid para o planner poder usá-lo.
     */
    @Query("""
            select p from Pagamento p
            where p.status = :status and p.cobranca is null
              and exists (select 1 from Cobranca c where c.txid = p.txid)
            order by p.pagoEm asc, p.id asc
            """)
    List<Pagamento> listarAguardandoComCobranca(@Param("status") StatusPagamento status, Pageable pageable);

    /** Relê UM pagamento travando a linha e pulando-a se outro processo já a travou. Dentro de transação. */
    @Query(value = """
            SELECT * FROM pagamento
            WHERE id = :id AND status = :status
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    Optional<Pagamento> bloquearAguardando(@Param("id") Long id, @Param("status") String status);

    /**
     * Idempotência pelo banco. Linhas afetadas = 0 significa que o endToEndId já existia.
     * Não usamos save() + captura de DataIntegrityViolationException: no PostgreSQL a violação
     * aborta a transação inteira e não dá mais para continuar nela.
     */
    @Modifying
    @Query(value = """
            INSERT INTO pagamento (end_to_end_id, txid, valor, status, pago_em, recebido_em)
            VALUES (:endToEndId, :txid, :valor, :status, :pagoEm, :recebidoEm)
            ON CONFLICT (end_to_end_id) DO NOTHING
            """, nativeQuery = true)
    int inserirSeNovo(@Param("endToEndId") String endToEndId,
                      @Param("txid") String txid,
                      @Param("valor") BigDecimal valor,
                      @Param("status") String status,
                      @Param("pagoEm") Instant pagoEm,
                      @Param("recebidoEm") Instant recebidoEm);

    /** @return true se o pagamento era novo; false se já existia (duplicado). */
    default boolean registrarSeNovo(String endToEndId, String txid, BigDecimal valor,
                                    Instant pagoEm, Instant recebidoEm) {
        return inserirSeNovo(endToEndId, txid, valor, StatusPagamento.AGUARDANDO_COBRANCA.name(),
                pagoEm, recebidoEm) == 1;
    }

    /**
     * Reserva saldo para uma devolução em UM comando atômico: soma ao valor_devolvido SOMENTE se o total
     * continuar dentro do valor pago. A condição é reavaliada pelo PostgreSQL depois de esperar qualquer
     * UPDATE concorrente na mesma linha, então duas devoluções simultâneas nunca ultrapassam o saldo.
     * Ler, somar em Java e gravar perderia devoluções concorrentes. Linhas afetadas = 0: saldo insuficiente.
     * O CHECK ck_pagamento_valor_devolvido é a última linha de defesa.
     */
    @Modifying
    @Query(value = """
            UPDATE pagamento
            SET valor_devolvido = valor_devolvido + :valor, version = version + 1
            WHERE id = :id AND valor_devolvido + :valor <= valor
            """, nativeQuery = true)
    int reservarSaldo(@Param("id") Long id, @Param("valor") BigDecimal valor);

    /** Estorno de uma devolução NAO_REALIZADO: devolve o saldo, nunca abaixo de zero. */
    @Modifying
    @Query(value = """
            UPDATE pagamento
            SET valor_devolvido = valor_devolvido - :valor, version = version + 1
            WHERE id = :id AND valor_devolvido - :valor >= 0
            """, nativeQuery = true)
    int estornarSaldo(@Param("id") Long id, @Param("valor") BigDecimal valor);

    /** Lê o valor direto do banco (a entidade pode estar desatualizada depois de um UPDATE nativo). */
    @Query("select p.valorDevolvido from Pagamento p where p.id = :id")
    BigDecimal buscarValorDevolvido(@Param("id") Long id);
}
