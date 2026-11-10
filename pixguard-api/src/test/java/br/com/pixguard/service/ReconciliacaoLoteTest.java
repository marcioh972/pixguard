package br.com.pixguard.service;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.pixguard.domain.TipoCobranca;
import br.com.pixguard.support.IntegracaoBase;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

@DisplayName("Job de reconciliação: tamanho do lote")
@TestPropertySource(properties = "pixguard.job.tamanho-lote=2")
class ReconciliacaoLoteTest extends IntegracaoBase {

    private static final String TXID_1 = "a1b2c3d4e5f60718293a4b5c6d7e8f90";
    private static final String TXID_2 = "b2c3d4e5f60718293a4b5c6d7e8f9012a";
    private static final String TXID_3 = "c3d4e5f60718293a4b5c6d7e8f9012abc";

    @Autowired
    private ReconciliacaoService servico;

    @Autowired
    private CobrancaService cobrancaService;

    private void criarCobranca(String txid) {
        txids.proximo(txid);
        cobrancaService.criar(TipoCobranca.IMEDIATA, new BigDecimal("110.00"), null);
    }

    private String statusCobranca(String txid) {
        return jdbc.queryForObject("SELECT status FROM cobranca WHERE txid = ?", String.class, txid);
    }

    @Test
    @DisplayName("cada ciclo processa no máximo o tamanho do lote, das mais antigas para as mais novas")
    void respeitaTamanhoDoLoteComAsMaisAntigasPrimeiro() {
        criarCobranca(TXID_1);
        relogio.avancar(Duration.ofMinutes(1));
        criarCobranca(TXID_2);
        relogio.avancar(Duration.ofMinutes(1));
        criarCobranca(TXID_3);
        relogio.definir(Instant.parse("2026-11-05T15:00:00Z"));

        ResumoCobrancas primeiro = servico.processarCobrancasSemRetorno();

        assertThat(primeiro.expiradas()).isEqualTo(2);
        assertThat(statusCobranca(TXID_1)).isEqualTo("EXPIRADA");
        assertThat(statusCobranca(TXID_2)).isEqualTo("EXPIRADA");
        assertThat(statusCobranca(TXID_3)).isEqualTo("ATIVA");
        assertThat(banco.consultas()).containsExactly(TXID_1, TXID_2);

        ResumoCobrancas segundo = servico.processarCobrancasSemRetorno();

        assertThat(segundo.expiradas()).isEqualTo(1);
        assertThat(statusCobranca(TXID_3)).isEqualTo("EXPIRADA");
    }
}
