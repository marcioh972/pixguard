package br.com.pixguard.service;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.pixguard.client.PagamentoNoBanco;
import br.com.pixguard.config.JobProperties;
import br.com.pixguard.domain.TipoCobranca;
import br.com.pixguard.support.IntegracaoBase;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;

@DisplayName("Job de reconciliação (ReconciliacaoService)")
class ReconciliacaoServiceTest extends IntegracaoBase {

    private static final String TXID = "a1b2c3d4e5f60718293a4b5c6d7e8f90";
    private static final String TXID_2 = "b2c3d4e5f60718293a4b5c6d7e8f9012a";
    private static final String E2E_1 = "E00000000202611051200abcdef00001";
    private static final String E2E_2 = "E00000000202611051200abcdef00002";

    // Relógio inicial = 12:00Z; cobrança imediata criada nele expira às 13:00Z.
    private static final Instant PAGO_12_30 = Instant.parse("2026-11-05T12:30:00Z");
    private static final Instant PAGO_13_30 = Instant.parse("2026-11-05T13:30:00Z");

    @Autowired
    private ReconciliacaoService servico;

    @Autowired
    private CobrancaService cobrancaService;

    @Autowired
    private PagamentoService pagamentoService;

    @Autowired
    private JobProperties job;

    @Autowired
    private DataSource dataSource;

    // ---------- helpers ----------

    private void criarCobranca(String txid) {
        txids.proximo(txid);
        cobrancaService.criar(TipoCobranca.IMEDIATA, new BigDecimal("110.00"), null);
    }

    /** 14:00Z: bem depois de expira_em (13:00Z) + tolerância. */
    private void passarDoPrazo() {
        relogio.avancar(Duration.ofHours(2));
    }

    private static PagamentoNoBanco pagoPeloBanco(String e2e, Instant horario) {
        return new PagamentoNoBanco(e2e, new BigDecimal("110.00"), horario);
    }

    private void inserirPagamentoAguardando(String e2e, String txid, String pagoEm) {
        jdbc.update("""
                INSERT INTO pagamento (end_to_end_id, txid, valor, status, pago_em, recebido_em)
                VALUES (?, ?, 110.00, 'AGUARDANDO_COBRANCA', ?::timestamptz, ?::timestamptz)
                """, e2e, txid, pagoEm, pagoEm);
    }

    private long contar(String sql, Object... args) {
        Long n = jdbc.queryForObject(sql, Long.class, args);
        return n == null ? 0 : n;
    }

    private String statusPagamento(String e2e) {
        return jdbc.queryForObject("SELECT status FROM pagamento WHERE end_to_end_id = ?", String.class, e2e);
    }

    private String statusCobranca(String txid) {
        return jdbc.queryForObject("SELECT status FROM cobranca WHERE txid = ?", String.class, txid);
    }

    // ---------- (a) cobranças sem retorno ----------

    @Test
    @DisplayName("banco diz que pagou (webhook perdido): baixa a cobrança pelo caminho único, com o horário do banco")
    void bancoDizPagaBaixaACobranca() {
        criarCobranca(TXID);
        banco.pagou(TXID, pagoPeloBanco(E2E_1, PAGO_12_30));
        passarDoPrazo();

        ResumoCobrancas resumo = servico.processarCobrancasSemRetorno();

        assertThat(resumo.baixadas()).isEqualTo(1);
        assertThat(statusPagamento(E2E_1)).isEqualTo("CONCILIADO");
        assertThat(statusCobranca(TXID)).isEqualTo("PAGA");
        Timestamp pagoEm = jdbc.queryForObject(
                "SELECT pago_em FROM pagamento WHERE end_to_end_id = ?", Timestamp.class, E2E_1);
        assertThat(pagoEm.toInstant()).isEqualTo(PAGO_12_30);
    }

    @Test
    @DisplayName("banco diz que pagou, mas DEPOIS do prazo: RECEBIDO_APOS_EXPIRACAO e a cobrança expira")
    void bancoDizPagaAposOPrazo() {
        criarCobranca(TXID);
        banco.pagou(TXID, pagoPeloBanco(E2E_1, PAGO_13_30));
        passarDoPrazo();

        ResumoCobrancas resumo = servico.processarCobrancasSemRetorno();

        assertThat(resumo.expiradas()).isEqualTo(1);
        assertThat(statusPagamento(E2E_1)).isEqualTo("RECEBIDO_APOS_EXPIRACAO");
        assertThat(statusCobranca(TXID)).isEqualTo("EXPIRADA");
    }

    @Test
    @DisplayName("banco diz que não pagou: a cobrança expira e nenhum pagamento é criado")
    void bancoDizNaoPagaExpiraACobranca() {
        criarCobranca(TXID);
        passarDoPrazo();

        ResumoCobrancas resumo = servico.processarCobrancasSemRetorno();

        assertThat(resumo.expiradas()).isEqualTo(1);
        assertThat(statusCobranca(TXID)).isEqualTo("EXPIRADA");
        assertThat(contar("SELECT count(*) FROM pagamento")).isZero();
    }

    @Test
    @DisplayName("pagamento já recebido após a expiração: a consulta do banco não o duplica e a cobrança expira")
    void pagamentoJaConhecidoNaoDuplica() {
        criarCobranca(TXID);
        passarDoPrazo();
        pagamentoService.processar(new PixRecebido(E2E_1, TXID, new BigDecimal("110.00"), PAGO_13_30));
        banco.pagou(TXID, pagoPeloBanco(E2E_1, PAGO_13_30));

        servico.processarCobrancasSemRetorno();

        assertThat(contar("SELECT count(*) FROM pagamento")).isEqualTo(1);
        assertThat(statusPagamento(E2E_1)).isEqualTo("RECEBIDO_APOS_EXPIRACAO");
        assertThat(statusCobranca(TXID)).isEqualTo("EXPIRADA");
    }

    @Test
    @DisplayName("erro do banco: loga e não altera nada; na rodada seguinte, com o banco de volta, processa")
    void bancoIndisponivelNaoAlteraNada() {
        criarCobranca(TXID);
        banco.pagou(TXID, pagoPeloBanco(E2E_1, PAGO_12_30));
        banco.indisponivel(TXID);
        passarDoPrazo();

        ResumoCobrancas falha = servico.processarCobrancasSemRetorno();

        assertThat(falha.falhas()).isEqualTo(1);
        assertThat(statusCobranca(TXID)).isEqualTo("ATIVA");
        assertThat(contar("SELECT count(*) FROM pagamento")).isZero();

        banco.voltou(TXID);
        ResumoCobrancas recuperada = servico.processarCobrancasSemRetorno();

        assertThat(recuperada.baixadas()).isEqualTo(1);
        assertThat(statusCobranca(TXID)).isEqualTo("PAGA");
    }

    @Test
    @DisplayName("antes de expira_em + tolerância a cobrança não é consultada; depois, é")
    void respeitaPrazoETolerancia() {
        criarCobranca(TXID);

        servico.processarCobrancasSemRetorno();
        assertThat(banco.consultas()).as("ainda dentro do prazo").isEmpty();

        relogio.avancar(Duration.ofHours(1).plus(job.toleranciaExpiracao()).minusSeconds(1));
        servico.processarCobrancasSemRetorno();
        assertThat(banco.consultas()).as("prazo venceu, mas ainda na tolerância").isEmpty();
        assertThat(statusCobranca(TXID)).isEqualTo("ATIVA");

        relogio.avancar(Duration.ofSeconds(1));
        servico.processarCobrancasSemRetorno();
        assertThat(banco.consultas()).as("passou de expira_em + tolerância").containsExactly(TXID);
        assertThat(statusCobranca(TXID)).isEqualTo("EXPIRADA");
    }

    @Test
    @DisplayName("cobrança já PAGA ou EXPIRADA nunca é consultada de novo")
    void soConsultaAtivas() {
        criarCobranca(TXID);
        passarDoPrazo();
        servico.processarCobrancasSemRetorno();
        banco.limpar();

        servico.processarCobrancasSemRetorno();

        assertThat(banco.consultas()).isEmpty();
    }

    @Test
    @DisplayName("uma falha não derruba o lote: o item que falha é desfeito e o seguinte é processado")
    void umaFalhaNaoDerrubaOLote() {
        criarCobranca(TXID);
        relogio.avancar(Duration.ofMinutes(1));
        criarCobranca(TXID_2);
        // endToEndId com 31 caracteres: o INSERT viola o CHECK do banco DENTRO da transação do item.
        banco.pagou(TXID, pagoPeloBanco(E2E_1.substring(1), PAGO_12_30));
        banco.pagou(TXID_2, pagoPeloBanco(E2E_2, PAGO_12_30));
        relogio.definir(Instant.parse("2026-11-05T15:00:00Z"));

        ResumoCobrancas resumo = servico.processarCobrancasSemRetorno();

        assertThat(resumo.falhas()).isEqualTo(1);
        assertThat(resumo.baixadas()).isEqualTo(1);
        assertThat(statusCobranca(TXID)).as("rollback: nem expirou").isEqualTo("ATIVA");
        assertThat(contar("SELECT count(*) FROM pagamento WHERE end_to_end_id = ?", E2E_1.substring(1))).isZero();
        assertThat(statusCobranca(TXID_2)).isEqualTo("PAGA");
        assertThat(statusPagamento(E2E_2)).isEqualTo("CONCILIADO");
    }

    @Test
    @Timeout(60)
    @DisplayName("SKIP LOCKED: cobrança travada por outro processo é ignorada, sem esperar, e processada depois")
    void cobrancaTravadaPorOutroProcessoEIgnorada() throws Exception {
        criarCobranca(TXID);
        passarDoPrazo();
        Long id = jdbc.queryForObject("SELECT id FROM cobranca WHERE txid = ?", Long.class, TXID);

        CountDownLatch travada = new CountDownLatch(1);
        CountDownLatch liberar = new CountDownLatch(1);
        ExecutorService outroProcesso = Executors.newSingleThreadExecutor();
        try {
            // "Outra instância do job" segurando o lock da linha, numa conexão própria.
            Future<Void> trava = outroProcesso.submit(() -> {
                try (Connection conexao = dataSource.getConnection()) {
                    conexao.setAutoCommit(false);
                    try (Statement comando = conexao.createStatement()) {
                        comando.execute("SELECT id FROM cobranca WHERE id = " + id + " FOR UPDATE");
                    }
                    travada.countDown();
                    liberar.await();
                    conexao.rollback();
                }
                return null;
            });
            assertThat(travada.await(10, TimeUnit.SECONDS)).isTrue();

            ResumoCobrancas durante = servico.processarCobrancasSemRetorno();

            liberar.countDown();
            trava.get(10, TimeUnit.SECONDS);
            assertThat(durante.ignoradas()).isEqualTo(1);
            assertThat(statusCobranca(TXID)).isEqualTo("ATIVA");
        } finally {
            liberar.countDown();
            outroProcesso.shutdownNow();
        }

        ResumoCobrancas depois = servico.processarCobrancasSemRetorno();

        assertThat(depois.expiradas()).isEqualTo(1);
        assertThat(statusCobranca(TXID)).isEqualTo("EXPIRADA");
    }

    @Test
    @Timeout(60)
    @DisplayName("dois ciclos ao mesmo tempo sobre a mesma cobrança: exatamente uma baixa")
    void doisCiclosConcorrentesBaixamUmaVez() throws Exception {
        criarCobranca(TXID);
        banco.pagou(TXID, pagoPeloBanco(E2E_1, PAGO_12_30));
        passarDoPrazo();

        CyclicBarrier largada = new CyclicBarrier(2);
        Callable<ResumoCobrancas> ciclo = () -> {
            largada.await(10, TimeUnit.SECONDS);
            return servico.processarCobrancasSemRetorno();
        };
        ExecutorService pool = Executors.newFixedThreadPool(2);
        ResumoCobrancas primeiro;
        ResumoCobrancas segundo;
        try {
            Future<ResumoCobrancas> f1 = pool.submit(ciclo);
            Future<ResumoCobrancas> f2 = pool.submit(ciclo);
            primeiro = f1.get(30, TimeUnit.SECONDS);
            segundo = f2.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        assertThat(primeiro.baixadas() + segundo.baixadas()).isEqualTo(1);
        assertThat(primeiro.falhas() + segundo.falhas()).isZero();
        assertThat(contar("SELECT count(*) FROM pagamento")).isEqualTo(1);
        assertThat(contar("SELECT count(*) FROM pagamento WHERE status = 'CONCILIADO'")).isEqualTo(1);
        assertThat(statusCobranca(TXID)).isEqualTo("PAGA");
    }

    // ---------- (b) rede de segurança ----------

    @Test
    @DisplayName("webhookAntesDaCobrancaConciliadoPeloJob: pagamento aguardando cuja cobrança já existe é conciliado")
    void webhookAntesDaCobrancaConciliadoPeloJob() {
        criarCobranca(TXID);
        // Corrida: o webhook gravou o pagamento sem ver a cobrança que estava sendo criada.
        inserirPagamentoAguardando(E2E_1, TXID, "2026-11-05T12:30:00Z");

        ResumoPagamentos resumo = servico.reprocessarPagamentosAguardando();

        assertThat(resumo.reprocessados()).isEqualTo(1);
        assertThat(statusPagamento(E2E_1)).isEqualTo("CONCILIADO");
        assertThat(statusCobranca(TXID)).isEqualTo("PAGA");
    }

    @Test
    @DisplayName("pagamento aguardando SEM cobrança continua aguardando (nunca é descartado)")
    void pagamentoSemCobrancaContinuaAguardando() {
        inserirPagamentoAguardando(E2E_1, TXID, "2026-11-05T12:30:00Z");

        ResumoPagamentos resumo = servico.reprocessarPagamentosAguardando();

        assertThat(resumo.reprocessados()).isZero();
        assertThat(statusPagamento(E2E_1)).isEqualTo("AGUARDANDO_COBRANCA");
    }

    @Test
    @DisplayName("pagamento aguardando para cobrança já paga vira EXCEDENTE pelo job")
    void aguardandoParaCobrancaPagaViraExcedente() {
        criarCobranca(TXID);
        pagamentoService.processar(new PixRecebido(E2E_1, TXID, new BigDecimal("110.00"), PAGO_12_30));
        inserirPagamentoAguardando(E2E_2, TXID, "2026-11-05T12:40:00Z");

        servico.reprocessarPagamentosAguardando();

        assertThat(statusPagamento(E2E_2)).isEqualTo("EXCEDENTE");
        assertThat(contar("SELECT count(*) FROM pagamento WHERE status = 'CONCILIADO'")).isEqualTo(1);
    }

    // ---------- ciclo completo ----------

    @Test
    @DisplayName("ciclo completo: reprocessa o pagamento aguardando ANTES e só consulta o banco para o que sobrou")
    void cicloCompleto() {
        criarCobranca(TXID);
        criarCobranca(TXID_2);
        inserirPagamentoAguardando(E2E_1, TXID, "2026-11-05T12:30:00Z");
        passarDoPrazo();

        ResumoCiclo resumo = servico.executarCiclo();

        assertThat(resumo.pagamentos().reprocessados()).isEqualTo(1);
        assertThat(resumo.cobrancas().expiradas()).isEqualTo(1);
        assertThat(statusCobranca(TXID)).isEqualTo("PAGA");
        assertThat(statusCobranca(TXID_2)).isEqualTo("EXPIRADA");
        assertThat(banco.consultas()).containsExactly(TXID_2);
    }
}
