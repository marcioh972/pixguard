package br.com.pixguard.repository;

import br.com.pixguard.domain.Devolucao;
import br.com.pixguard.domain.StatusDevolucao;
import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DevolucaoRepository extends JpaRepository<Devolucao, Long> {

    Optional<Devolucao> findByIdDevolucao(String idDevolucao);

    Page<Devolucao> findByPagamentoId(Long pagamentoId, Pageable pageable);

    /** Trava a devolução para registrar o resultado: dois resultados simultâneos são serializados. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from Devolucao d where d.idDevolucao = :idDevolucao")
    Optional<Devolucao> buscarParaAtualizar(@Param("idDevolucao") String idDevolucao);

    /**
     * Idempotência pelo banco. Linhas afetadas = 0 significa que o idDevolucao já existia. Se uma chamada
     * idêntica estiver em andamento, esta espera ela terminar: se ela confirmar, vira repetição; se ela
     * der rollback (ex.: saldo insuficiente), esta passa a ser a devolução nova.
     */
    @Modifying
    @Query(value = """
            INSERT INTO devolucao (id_devolucao, pagamento_id, valor, status, motivo, solicitada_em)
            VALUES (:idDevolucao, :pagamentoId, :valor, :status, CAST(:motivo AS VARCHAR), :solicitadaEm)
            ON CONFLICT (id_devolucao) DO NOTHING
            """, nativeQuery = true)
    int inserirSeNova(@Param("idDevolucao") String idDevolucao,
                      @Param("pagamentoId") Long pagamentoId,
                      @Param("valor") BigDecimal valor,
                      @Param("status") String status,
                      @Param("motivo") String motivo,
                      @Param("solicitadaEm") Instant solicitadaEm);

    /** @return true se a devolução era nova; false se o idDevolucao já existia. */
    default boolean registrarSeNova(String idDevolucao, Long pagamentoId, BigDecimal valor,
                                    String motivo, Instant solicitadaEm) {
        return inserirSeNova(idDevolucao, pagamentoId, valor, StatusDevolucao.EM_PROCESSAMENTO.name(),
                motivo, solicitadaEm) == 1;
    }
}
