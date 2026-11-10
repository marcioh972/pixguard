package br.com.pixguard.repository;

import br.com.pixguard.domain.Cobranca;
import br.com.pixguard.domain.StatusCobranca;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CobrancaRepository extends JpaRepository<Cobranca, Long> {

    Optional<Cobranca> findByTxid(String txid);

    /**
     * Candidatas do job: ATIVAS com expira_em até o limite, mais antigas primeiro. É a consulta que o
     * índice parcial idx_cobranca_ativa_expira_em atende. Sem lock: é só a lista de quem olhar;
     * o lock é tomado por cobrança, na transação de cada item (ver bloquearAtivaVencida).
     */
    List<Cobranca> findByStatusAndExpiraEmLessThanEqualOrderByExpiraEmAscIdAsc(
            StatusCobranca status, Instant limite, Pageable pageable);

    /**
     * Relê UMA cobrança travando a linha, pulando-a se outro processo já a travou (SKIP LOCKED) e
     * devolvendo vazio se ela deixou de ser ATIVA/vencida desde a listagem. O lock é por item porque
     * um lock de lote seguraria as linhas enquanto cada item abre outra transação (auto-bloqueio).
     * Deve ser chamado dentro de uma transação.
     */
    @Query(value = """
            SELECT * FROM cobranca
            WHERE id = :id AND status = :status AND expira_em <= :limite
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    Optional<Cobranca> bloquearAtivaVencida(@Param("id") Long id,
                                            @Param("status") String status,
                                            @Param("limite") Instant limite);
}
