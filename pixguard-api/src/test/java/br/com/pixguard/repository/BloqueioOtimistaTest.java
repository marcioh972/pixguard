package br.com.pixguard.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.pixguard.domain.Cobranca;
import br.com.pixguard.domain.Devolucao;
import br.com.pixguard.domain.Pagamento;
import br.com.pixguard.domain.StatusCobranca;
import br.com.pixguard.support.IntegracaoBase;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.OptimisticLockingFailureException;

/**
 * @Version nas três entidades. Cada teste pega uma cópia velha da entidade, deixa "outra transação" mudar a
 * linha (a version sobe) e prova que gravar a cópia velha é recusado, em vez de sobrescrever em silêncio.
 */
@DisplayName("Bloqueio otimista (@Version) em cobranca, pagamento e devolucao")
class BloqueioOtimistaTest extends IntegracaoBase {

    private static final String TXID = "a1b2c3d4e5f60718293a4b5c6d7e8f90";
    private static final String E2E = "E00000000202611051200abcdef00001";
    private static final String E2E_AGUARDANDO = "E00000000202611051200abcdef00002";
    private static final Instant CRIADA = Instant.parse("2026-11-05T12:00:00Z");
    private static final Instant AGORA = CRIADA.plusSeconds(60);

    @Autowired
    private CobrancaRepository cobrancaRepository;

    @Autowired
    private PagamentoRepository pagamentoRepository;

    @Autowired
    private DevolucaoRepository devolucaoRepository;

    @BeforeEach
    void criarLinhas() {
        Long cobrancaId = jdbc.queryForObject("""
                INSERT INTO cobranca (txid, tipo, valor, status, criada_em, expira_em, atualizada_em)
                VALUES (?, 'IMEDIATA', 100.00, 'PAGA', ?::timestamptz, ?::timestamptz, ?::timestamptz)
                RETURNING id
                """, Long.class, TXID, CRIADA.toString(), CRIADA.plusSeconds(3600).toString(), CRIADA.toString());
        Long pagamentoId = jdbc.queryForObject("""
                INSERT INTO pagamento (end_to_end_id, txid, cobranca_id, valor, status, pago_em, recebido_em)
                VALUES (?, ?, ?, 100.00, 'CONCILIADO', now(), now())
                RETURNING id
                """, Long.class, E2E, TXID, cobrancaId);
        // Um segundo pagamento, ainda aguardando cobrança, para ter uma transição de estado válida a tentar.
        jdbc.update("""
                INSERT INTO pagamento (end_to_end_id, txid, valor, status, pago_em, recebido_em)
                VALUES (?, ?, 100.00, 'AGUARDANDO_COBRANCA', now(), now())
                """, E2E_AGUARDANDO, TXID);
        jdbc.update("""
                INSERT INTO devolucao (id_devolucao, pagamento_id, valor, status, solicitada_em)
                VALUES ('dev001', ?, 10.00, 'EM_PROCESSAMENTO', now())
                """, pagamentoId);
    }

    private long versionNoBanco(String tabela, String colunaChave, String chave) {
        Long version = jdbc.queryForObject(
                "SELECT version FROM " + tabela + " WHERE " + colunaChave + " = ?", Long.class, chave);
        return version == null ? -1 : version;
    }

    @Test
    @DisplayName("cobranca: gravar uma cópia velha depois de outra alteração é recusado")
    void cobranca() {
        Cobranca copiaVelha = cobrancaRepository.findByTxid(TXID).orElseThrow();
        jdbc.update("UPDATE cobranca SET version = version + 1 WHERE txid = ?", TXID); // "outra transação"
        copiaVelha.atualizarPorDevolucao(new BigDecimal("40.00"), new BigDecimal("100.00"), AGORA);

        assertThatThrownBy(() -> cobrancaRepository.save(copiaVelha))
                .isInstanceOf(OptimisticLockingFailureException.class);

        assertThat(jdbc.queryForObject("SELECT status FROM cobranca WHERE txid = ?", String.class, TXID))
                .as("a alteração da cópia velha não foi gravada").isEqualTo(StatusCobranca.PAGA.name());
    }

    @Test
    @DisplayName("cobranca: sem concorrência, a gravação passa e a version sobe")
    void cobrancaSemConflitoIncrementaVersion() {
        Cobranca atual = cobrancaRepository.findByTxid(TXID).orElseThrow();
        long antes = versionNoBanco("cobranca", "txid", TXID);
        atual.atualizarPorDevolucao(new BigDecimal("40.00"), new BigDecimal("100.00"), AGORA);

        cobrancaRepository.save(atual);

        assertThat(versionNoBanco("cobranca", "txid", TXID)).isEqualTo(antes + 1);
    }

    @Test
    @DisplayName("pagamento: gravar uma cópia velha depois de outra alteração é recusado")
    void pagamento() {
        Pagamento copiaVelha = pagamentoRepository.findByEndToEndId(E2E_AGUARDANDO).orElseThrow();
        Cobranca cobranca = cobrancaRepository.findByTxid(TXID).orElseThrow();
        jdbc.update("UPDATE pagamento SET version = version + 1 WHERE end_to_end_id = ?", E2E_AGUARDANDO);
        copiaVelha.marcarExcedente(cobranca);

        assertThatThrownBy(() -> pagamentoRepository.save(copiaVelha))
                .isInstanceOf(OptimisticLockingFailureException.class);

        assertThat(jdbc.queryForObject("SELECT status FROM pagamento WHERE end_to_end_id = ?", String.class,
                E2E_AGUARDANDO)).as("a alteração da cópia velha não foi gravada").isEqualTo("AGUARDANDO_COBRANCA");
    }

    @Test
    @DisplayName("pagamento: sem concorrência, a gravação passa e a version sobe")
    void pagamentoSemConflitoIncrementaVersion() {
        Pagamento atual = pagamentoRepository.findByEndToEndId(E2E_AGUARDANDO).orElseThrow();
        Cobranca cobranca = cobrancaRepository.findByTxid(TXID).orElseThrow();
        long antes = versionNoBanco("pagamento", "end_to_end_id", E2E_AGUARDANDO);
        atual.marcarExcedente(cobranca);

        pagamentoRepository.save(atual);

        assertThat(versionNoBanco("pagamento", "end_to_end_id", E2E_AGUARDANDO)).isEqualTo(antes + 1);
        assertThat(jdbc.queryForObject("SELECT status FROM pagamento WHERE end_to_end_id = ?", String.class,
                E2E_AGUARDANDO)).isEqualTo("EXCEDENTE");
    }

    @Test
    @DisplayName("devolucao: gravar uma cópia velha depois de outra alteração é recusado")
    void devolucao() {
        Devolucao copiaVelha = devolucaoRepository.findByIdDevolucao("dev001").orElseThrow();
        jdbc.update("UPDATE devolucao SET version = version + 1 WHERE id_devolucao = 'dev001'");
        copiaVelha.concluir();

        assertThatThrownBy(() -> devolucaoRepository.save(copiaVelha))
                .isInstanceOf(OptimisticLockingFailureException.class);

        assertThat(jdbc.queryForObject("SELECT status FROM devolucao WHERE id_devolucao = 'dev001'", String.class))
                .isEqualTo("EM_PROCESSAMENTO");
    }
}
