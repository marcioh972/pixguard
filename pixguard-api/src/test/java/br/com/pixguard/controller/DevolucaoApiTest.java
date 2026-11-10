package br.com.pixguard.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.endsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.pixguard.config.DevolucaoProperties;
import br.com.pixguard.domain.TipoCobranca;
import br.com.pixguard.service.CobrancaService;
import br.com.pixguard.service.PagamentoService;
import br.com.pixguard.service.PixRecebido;
import br.com.pixguard.service.ResultadoProcessamento;
import br.com.pixguard.support.IntegracaoBase;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@DisplayName("Devoluções (PUT/GET /api/v1/pagamentos/{endToEndId}/devolucoes/{idDevolucao})")
class DevolucaoApiTest extends IntegracaoBase {

    private static final String TXID = "a1b2c3d4e5f60718293a4b5c6d7e8f90";
    private static final String TXID_2 = "b2c3d4e5f60718293a4b5c6d7e8f9012a";
    private static final String E2E = "E00000000202611051200abcdef00001";
    private static final String E2E_2 = "E00000000202611051200abcdef00002";

    // Relógio inicial = 12:00Z; o pagamento é "pago" às 12:05Z, dentro do prazo da cobrança.
    private static final Instant PAGO_EM = Instant.parse("2026-11-05T12:05:00Z");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CobrancaService cobrancaService;

    @Autowired
    private PagamentoService pagamentoService;

    @Autowired
    private DevolucaoProperties propriedades;

    // ---------- helpers ----------

    private void cobranca(String txid, String valor) {
        txids.proximo(txid);
        cobrancaService.criar(TipoCobranca.IMEDIATA, new BigDecimal(valor), null);
    }

    /** Cobrança paga: pagamento CONCILIADO de {@code valor}. */
    private void pagamentoConciliado(String e2e, String txid, String valor) {
        cobranca(txid, valor);
        ResultadoProcessamento r = pagamentoService.processar(new PixRecebido(e2e, txid, new BigDecimal(valor), PAGO_EM));
        assertThat(r).isEqualTo(ResultadoProcessamento.CONCILIADO);
    }

    private ResultActions devolverBruto(String e2e, String id, String json) throws Exception {
        return mockMvc.perform(put("/api/v1/pagamentos/{e2e}/devolucoes/{id}", e2e, id)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json));
    }

    private ResultActions devolver(String e2e, String id, String valor) throws Exception {
        return devolverBruto(e2e, id, "{\"valor\":\"%s\"}".formatted(valor));
    }

    private ResultActions resultado(String e2e, String id, String status) throws Exception {
        return mockMvc.perform(put("/api/v1/pagamentos/{e2e}/devolucoes/{id}/resultado", e2e, id)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"%s\"}".formatted(status)));
    }

    private BigDecimal valorDevolvido(String e2e) {
        return jdbc.queryForObject("SELECT valor_devolvido FROM pagamento WHERE end_to_end_id = ?",
                BigDecimal.class, e2e);
    }

    private String statusCobranca(String txid) {
        return jdbc.queryForObject("SELECT status FROM cobranca WHERE txid = ?", String.class, txid);
    }

    private long contar(String sql, Object... args) {
        Long n = jdbc.queryForObject(sql, Long.class, args);
        return n == null ? 0 : n;
    }

    /** Invariante central: o saldo devolvido é exatamente a soma das devoluções que não foram NAO_REALIZADO. */
    private void assertSaldoConsistente(String e2e) {
        BigDecimal soma = jdbc.queryForObject("""
                SELECT COALESCE(SUM(d.valor), 0)
                FROM devolucao d JOIN pagamento p ON p.id = d.pagamento_id
                WHERE p.end_to_end_id = ? AND d.status <> 'NAO_REALIZADO'
                """, BigDecimal.class, e2e);
        assertThat(valorDevolvido(e2e)).isEqualByComparingTo(soma);
    }

    @FunctionalInterface
    private interface ChamadaHttp {
        ResultActions executar(int indice) throws Exception;
    }

    /** Dispara {@code quantidade} chamadas liberadas juntas por uma CyclicBarrier e devolve os status HTTP. */
    private List<Integer> emParalelo(int quantidade, ChamadaHttp chamada) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(quantidade);
        CyclicBarrier largada = new CyclicBarrier(quantidade);
        try {
            List<Future<Integer>> futuros = new ArrayList<>();
            for (int i = 0; i < quantidade; i++) {
                int indice = i;
                futuros.add(pool.submit(() -> {
                    largada.await(10, TimeUnit.SECONDS);
                    return chamada.executar(indice).andReturn().getResponse().getStatus();
                }));
            }
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> futuro : futuros) {
                statuses.add(futuro.get(30, TimeUnit.SECONDS));
            }
            return statuses;
        } finally {
            pool.shutdownNow();
        }
    }

    private static long quantos(List<Integer> statuses, int status) {
        return statuses.stream().filter(s -> s == status).count();
    }

    // ---------- caminho feliz ----------

    @Test
    @DisplayName("devolução parcial: 201, saldo reservado, cobrança DEVOLVIDA_PARCIAL")
    void devolucaoParcial() throws Exception {
        pagamentoConciliado(E2E, TXID, "100.00");

        devolver(E2E, "dev001", "40.00")
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.LOCATION, endsWith("/devolucoes/dev001")))
                .andExpect(jsonPath("$.idDevolucao").value("dev001"))
                .andExpect(jsonPath("$.endToEndId").value(E2E))
                .andExpect(jsonPath("$.valor").value("40.00"))
                .andExpect(jsonPath("$.status").value("EM_PROCESSAMENTO"));

        assertThat(valorDevolvido(E2E)).isEqualByComparingTo("40.00");
        assertThat(statusCobranca(TXID)).isEqualTo("DEVOLVIDA_PARCIAL");
        assertSaldoConsistente(E2E);
    }

    @Test
    @DisplayName("devolução do valor inteiro: cobrança DEVOLVIDA_TOTAL")
    void devolucaoTotal() throws Exception {
        pagamentoConciliado(E2E, TXID, "100.00");

        devolver(E2E, "dev001", "100.00").andExpect(status().isCreated());

        assertThat(valorDevolvido(E2E)).isEqualByComparingTo("100.00");
        assertThat(statusCobranca(TXID)).isEqualTo("DEVOLVIDA_TOTAL");
    }

    @Test
    @DisplayName("devolução com motivo guarda o motivo")
    void devolucaoComMotivo() throws Exception {
        pagamentoConciliado(E2E, TXID, "100.00");

        devolverBruto(E2E, "dev001", "{\"valor\":\"10.00\",\"motivo\":\"produto com defeito\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.motivo").value("produto com defeito"));
    }

    @Test
    @DisplayName("devoluções parciais somam e a última, ao esgotar o saldo, torna a cobrança DEVOLVIDA_TOTAL")
    void devolucoesParciaisSomam() throws Exception {
        pagamentoConciliado(E2E, TXID, "100.00");

        devolver(E2E, "dev001", "70.00").andExpect(status().isCreated());
        assertThat(statusCobranca(TXID)).isEqualTo("DEVOLVIDA_PARCIAL");
        devolver(E2E, "dev002", "30.00").andExpect(status().isCreated());

        assertThat(valorDevolvido(E2E)).isEqualByComparingTo("100.00");
        assertThat(statusCobranca(TXID)).isEqualTo("DEVOLVIDA_TOTAL");
        devolver(E2E, "dev003", "0.01").andExpect(status().isUnprocessableEntity());
        assertSaldoConsistente(E2E);
    }

    // ---------- acima do saldo ----------

    @Test
    @DisplayName("devolucaoParcialAcimaDoSaldo: 422 e nada muda (a devolução inserida some junto)")
    void devolucaoParcialAcimaDoSaldo() throws Exception {
        pagamentoConciliado(E2E, TXID, "100.00");
        devolver(E2E, "dev001", "70.00").andExpect(status().isCreated());

        devolver(E2E, "dev002", "40.00")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(422));

        assertThat(valorDevolvido(E2E)).isEqualByComparingTo("70.00");
        assertThat(contar("SELECT count(*) FROM devolucao")).isEqualTo(1);
        assertThat(contar("SELECT count(*) FROM devolucao WHERE id_devolucao = 'dev002'")).isZero();
        assertThat(statusCobranca(TXID)).isEqualTo("DEVOLVIDA_PARCIAL");
        assertSaldoConsistente(E2E);
    }

    @Test
    @DisplayName("devolução maior que o valor pago: 422")
    void devolucaoMaiorQueOPagamento() throws Exception {
        pagamentoConciliado(E2E, TXID, "100.00");

        devolver(E2E, "dev001", "100.01").andExpect(status().isUnprocessableEntity());

        assertThat(valorDevolvido(E2E)).isEqualByComparingTo("0.00");
        assertThat(contar("SELECT count(*) FROM devolucao")).isZero();
        assertThat(statusCobranca(TXID)).isEqualTo("PAGA");
    }

    // ---------- fora do prazo ----------

    @Test
    @DisplayName("devolucaoForaDoPrazo: 1 segundo depois do prazo, 422 e nada é gravado")
    void devolucaoForaDoPrazo() throws Exception {
        pagamentoConciliado(E2E, TXID, "100.00");
        relogio.definir(PAGO_EM.plus(propriedades.prazo()).plusSeconds(1));

        devolver(E2E, "dev001", "10.00")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.status").value(422));

        assertThat(valorDevolvido(E2E)).isEqualByComparingTo("0.00");
        assertThat(contar("SELECT count(*) FROM devolucao")).isZero();
        assertThat(statusCobranca(TXID)).isEqualTo("PAGA");
    }

    @Test
    @DisplayName("exatamente no limite do prazo a devolução ainda é aceita (limite inclusivo)")
    void devolucaoNoLimiteDoPrazoEAceita() throws Exception {
        pagamentoConciliado(E2E, TXID, "100.00");
        relogio.definir(PAGO_EM.plus(propriedades.prazo()));

        devolver(E2E, "dev001", "10.00").andExpect(status().isCreated());
    }

    // ---------- idempotência ----------

    @Test
    @DisplayName("idempotência: repetir o mesmo pedido devolve a devolução existente (200) e não debita de novo")
    void devolucaoRepetidaDevolveAExistente() throws Exception {
        pagamentoConciliado(E2E, TXID, "100.00");
        String primeira = devolver(E2E, "dev001", "40.00").andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        relogio.avancar(Duration.ofMinutes(5));
        String segunda = devolver(E2E, "dev001", "40.00").andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(segunda).isEqualTo(primeira);
        assertThat(valorDevolvido(E2E)).isEqualByComparingTo("40.00");
        assertThat(contar("SELECT count(*) FROM devolucao")).isEqualTo(1);
        assertSaldoConsistente(E2E);
    }

    @Test
    @DisplayName("repetição depois do prazo ainda devolve a devolução existente: reenvio de pedido que já deu certo não vira erro")
    void repeticaoAposOPrazoDevolveAExistente() throws Exception {
        pagamentoConciliado(E2E, TXID, "100.00");
        devolver(E2E, "dev001", "40.00").andExpect(status().isCreated());
        relogio.definir(PAGO_EM.plus(propriedades.prazo()).plusSeconds(1));

        devolver(E2E, "dev001", "40.00").andExpect(status().isOk());

        assertThat(valorDevolvido(E2E)).isEqualByComparingTo("40.00");
    }

    @Test
    @DisplayName("mesmo idDevolucao com valor diferente: 409 e nada muda")
    void mesmoIdComValorDiferente() throws Exception {
        pagamentoConciliado(E2E, TXID, "100.00");
        devolver(E2E, "dev001", "40.00").andExpect(status().isCreated());

        devolver(E2E, "dev001", "50.00")
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));

        assertThat(valorDevolvido(E2E)).isEqualByComparingTo("40.00");
        assertThat(contar("SELECT count(*) FROM devolucao")).isEqualTo(1);
    }

    @Test
    @DisplayName("mesmo idDevolucao usado em outro pagamento: 409 e o outro pagamento não é tocado")
    void mesmoIdEmOutroPagamento() throws Exception {
        pagamentoConciliado(E2E, TXID, "100.00");
        pagamentoConciliado(E2E_2, TXID_2, "100.00");
        devolver(E2E, "dev001", "40.00").andExpect(status().isCreated());

        devolver(E2E_2, "dev001", "40.00").andExpect(status().isConflict());

        assertThat(valorDevolvido(E2E_2)).isEqualByComparingTo("0.00");
        assertThat(statusCobranca(TXID_2)).isEqualTo("PAGA");
    }

    @Test
    @Timeout(60)
    @DisplayName("o MESMO pedido enviado ao mesmo tempo 6 vezes: uma criação, cinco repetições, saldo debitado uma vez")
    void repeticaoConcorrenteDebitaUmaVez() throws Exception {
        pagamentoConciliado(E2E, TXID, "100.00");

        List<Integer> statuses = emParalelo(6, i -> devolver(E2E, "dev001", "40.00"));

        assertThat(quantos(statuses, 201)).isEqualTo(1);
        assertThat(quantos(statuses, 200)).isEqualTo(5);
        assertThat(valorDevolvido(E2E)).isEqualByComparingTo("40.00");
        assertThat(contar("SELECT count(*) FROM devolucao")).isEqualTo(1);
        assertSaldoConsistente(E2E);
    }

    // ---------- concorrência de saldo ----------

    @Test
    @Timeout(60)
    @DisplayName("devoluções concorrentes: 2 x 60 sobre um pagamento de 100, só uma passa")
    void devolucoesConcorrentesSoUmaPassa() throws Exception {
        pagamentoConciliado(E2E, TXID, "100.00");

        List<Integer> statuses = emParalelo(2, i -> devolver(E2E, "dev00" + i, "60.00"));

        assertThat(quantos(statuses, 201)).isEqualTo(1);
        assertThat(quantos(statuses, 422)).isEqualTo(1);
        assertThat(valorDevolvido(E2E)).isEqualByComparingTo("60.00");
        assertThat(contar("SELECT count(*) FROM devolucao")).isEqualTo(1);
        assertThat(statusCobranca(TXID)).isEqualTo("DEVOLVIDA_PARCIAL");
        assertSaldoConsistente(E2E);
    }

    @Test
    @Timeout(60)
    @DisplayName("8 devoluções simultâneas de 30 sobre 100: exatamente 3 passam e o saldo nunca estoura")
    void oitoDevolucoesDeTrintaSobreCem() throws Exception {
        pagamentoConciliado(E2E, TXID, "100.00");

        List<Integer> statuses = emParalelo(8, i -> devolver(E2E, "dev00" + i, "30.00"));

        assertThat(quantos(statuses, 201)).isEqualTo(3);
        assertThat(quantos(statuses, 422)).isEqualTo(5);
        assertThat(valorDevolvido(E2E)).isEqualByComparingTo("90.00");
        assertThat(contar("SELECT count(*) FROM devolucao")).isEqualTo(3);
        assertThat(statusCobranca(TXID)).isEqualTo("DEVOLVIDA_PARCIAL");
        assertSaldoConsistente(E2E);
    }

    // ---------- elegibilidade ----------

    @Test
    @DisplayName("pagamento inexistente: 404")
    void pagamentoInexistente() throws Exception {
        devolver(E2E, "dev001", "10.00")
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    @DisplayName("pagamento ainda AGUARDANDO_COBRANCA não pode ser devolvido: 422 e nada é gravado")
    void pagamentoAguardandoCobrancaNaoPodeSerDevolvido() throws Exception {
        pagamentoService.processar(new PixRecebido(E2E, TXID, new BigDecimal("100.00"), PAGO_EM));

        devolver(E2E, "dev001", "10.00").andExpect(status().isUnprocessableEntity());

        assertThat(contar("SELECT count(*) FROM devolucao")).isZero();
        assertThat(valorDevolvido(E2E)).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("pagamento EXCEDENTE pode ser devolvido e a cobrança (paga por outro pagamento) não muda")
    void devolucaoDeExcedenteNaoMudaACobranca() throws Exception {
        pagamentoConciliado(E2E, TXID, "100.00");
        ResultadoProcessamento r = pagamentoService.processar(
                new PixRecebido(E2E_2, TXID, new BigDecimal("100.00"), PAGO_EM.plusSeconds(60)));
        assertThat(r).isEqualTo(ResultadoProcessamento.EXCEDENTE);

        devolver(E2E_2, "dev001", "100.00").andExpect(status().isCreated());

        assertThat(valorDevolvido(E2E_2)).isEqualByComparingTo("100.00");
        assertThat(valorDevolvido(E2E)).isEqualByComparingTo("0.00");
        assertThat(statusCobranca(TXID)).isEqualTo("PAGA");
    }

    @Test
    @DisplayName("pagamento RECEBIDO_APOS_EXPIRACAO é elegível a devolução e a cobrança não muda")
    void devolucaoDePagamentoAposExpiracao() throws Exception {
        cobranca(TXID, "100.00");
        relogio.avancar(Duration.ofHours(2));
        ResultadoProcessamento r = pagamentoService.processar(new PixRecebido(
                E2E, TXID, new BigDecimal("100.00"), Instant.parse("2026-11-05T13:30:00Z")));
        assertThat(r).isEqualTo(ResultadoProcessamento.RECEBIDO_APOS_EXPIRACAO);

        devolver(E2E, "dev001", "100.00").andExpect(status().isCreated());

        assertThat(valorDevolvido(E2E)).isEqualByComparingTo("100.00");
        assertThat(statusCobranca(TXID)).isEqualTo("ATIVA");
    }

    @Test
    @DisplayName("depois de DEVOLVIDA_TOTAL, um novo pagamento do mesmo txid vira EXCEDENTE (não há segunda baixa)")
    void novoPagamentoAposDevolucaoTotalViraExcedente() throws Exception {
        pagamentoConciliado(E2E, TXID, "100.00");
        devolver(E2E, "dev001", "100.00").andExpect(status().isCreated());

        ResultadoProcessamento r = pagamentoService.processar(
                new PixRecebido(E2E_2, TXID, new BigDecimal("100.00"), PAGO_EM.plusSeconds(120)));

        assertThat(r).isEqualTo(ResultadoProcessamento.EXCEDENTE);
        assertThat(statusCobranca(TXID)).isEqualTo("DEVOLVIDA_TOTAL");
    }

    // ---------- payload e caminho inválidos ----------

    static Stream<Arguments> payloadsInvalidos() {
        return Stream.of(
                Arguments.of("valor sem casas decimais", "{\"valor\":\"60\"}"),
                Arguments.of("valor com 1 casa decimal", "{\"valor\":\"60.5\"}"),
                Arguments.of("valor zero", "{\"valor\":\"0.00\"}"),
                Arguments.of("valor negativo", "{\"valor\":\"-1.00\"}"),
                Arguments.of("valor com vírgula", "{\"valor\":\"60,00\"}"),
                Arguments.of("valor ausente", "{}"),
                Arguments.of("motivo com 141 caracteres", "{\"valor\":\"60.00\",\"motivo\":\"" + "x".repeat(141) + "\"}"),
                Arguments.of("JSON malformado", "{valor"));
    }

    @ParameterizedTest(name = "{0}: 400 e nada é gravado")
    @MethodSource("payloadsInvalidos")
    @DisplayName("payload inválido devolve 400 em ProblemDetail e não grava nada")
    void payloadInvalido(String descricao, String json) throws Exception {
        pagamentoConciliado(E2E, TXID, "100.00");

        devolverBruto(E2E, "dev001", json)
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));

        assertThat(contar("SELECT count(*) FROM devolucao")).isZero();
        assertThat(valorDevolvido(E2E)).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("endToEndId com 31 caracteres no caminho: 400")
    void endToEndIdInvalidoNoCaminho() throws Exception {
        devolver(E2E.substring(1), "dev001", "10.00").andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("idDevolucao fora do formato no caminho: 400")
    void idDevolucaoInvalidoNoCaminho() throws Exception {
        pagamentoConciliado(E2E, TXID, "100.00");

        devolver(E2E, "com-hifen", "10.00").andExpect(status().isBadRequest());

        assertThat(contar("SELECT count(*) FROM devolucao")).isZero();
    }

    // ---------- consultas ----------

    @Test
    @DisplayName("GET do pagamento mostra o saldo devolvido")
    void consultaPagamento() throws Exception {
        pagamentoConciliado(E2E, TXID, "100.00");
        devolver(E2E, "dev001", "40.00").andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/pagamentos/{e2e}", E2E))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.endToEndId").value(E2E))
                .andExpect(jsonPath("$.status").value("CONCILIADO"))
                .andExpect(jsonPath("$.valor").value("100.00"))
                .andExpect(jsonPath("$.valorDevolvido").value("40.00"));
    }

    @Test
    @DisplayName("GET da devolução: 200 para a do próprio pagamento, 404 para outra ou inexistente")
    void consultaDevolucao() throws Exception {
        pagamentoConciliado(E2E, TXID, "100.00");
        pagamentoConciliado(E2E_2, TXID_2, "100.00");
        devolver(E2E, "dev001", "40.00").andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/pagamentos/{e2e}/devolucoes/{id}", E2E, "dev001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.idDevolucao").value("dev001"))
                .andExpect(jsonPath("$.valor").value("40.00"));
        mockMvc.perform(get("/api/v1/pagamentos/{e2e}/devolucoes/{id}", E2E_2, "dev001"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/pagamentos/{e2e}/devolucoes/{id}", E2E, "naoexiste"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("listagem de devoluções é paginada, ordenada por id e com tamanho limitado")
    void listagemPaginada() throws Exception {
        pagamentoConciliado(E2E, TXID, "100.00");
        devolver(E2E, "dev001", "10.00").andExpect(status().isCreated());
        devolver(E2E, "dev002", "10.00").andExpect(status().isCreated());
        devolver(E2E, "dev003", "10.00").andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/pagamentos/{e2e}/devolucoes?pagina=0&tamanho=2", E2E))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conteudo.length()").value(2))
                .andExpect(jsonPath("$.conteudo[0].idDevolucao").value("dev001"))
                .andExpect(jsonPath("$.conteudo[1].idDevolucao").value("dev002"))
                .andExpect(jsonPath("$.totalElementos").value(3))
                .andExpect(jsonPath("$.totalPaginas").value(2));
        mockMvc.perform(get("/api/v1/pagamentos/{e2e}/devolucoes?pagina=1&tamanho=2", E2E))
                .andExpect(jsonPath("$.conteudo.length()").value(1))
                .andExpect(jsonPath("$.conteudo[0].idDevolucao").value("dev003"));
        mockMvc.perform(get("/api/v1/pagamentos/{e2e}/devolucoes?tamanho=100000", E2E))
                .andExpect(jsonPath("$.tamanho").value(100));
        mockMvc.perform(get("/api/v1/pagamentos/{e2e}/devolucoes", E2E_2))
                .andExpect(status().isNotFound());
    }

    // ---------- resultado da devolução (NAO_REALIZADO estorna o saldo) ----------

    @Test
    @DisplayName("NAO_REALIZADO estorna o saldo, a cobrança volta a PAGA e o valor pode ser devolvido de novo")
    void devolucaoNaoRealizadaEstornaOSaldo() throws Exception {
        pagamentoConciliado(E2E, TXID, "100.00");
        devolver(E2E, "dev001", "100.00").andExpect(status().isCreated());
        assertThat(statusCobranca(TXID)).isEqualTo("DEVOLVIDA_TOTAL");

        resultado(E2E, "dev001", "NAO_REALIZADO")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NAO_REALIZADO"));

        assertThat(valorDevolvido(E2E)).isEqualByComparingTo("0.00");
        assertThat(statusCobranca(TXID)).isEqualTo("PAGA");
        assertSaldoConsistente(E2E);
        devolver(E2E, "dev002", "100.00").andExpect(status().isCreated());
        assertThat(statusCobranca(TXID)).isEqualTo("DEVOLVIDA_TOTAL");
        assertSaldoConsistente(E2E);
    }

    @Test
    @DisplayName("DEVOLVIDO confirma a devolução e mantém o saldo debitado")
    void devolucaoConcluidaMantemOSaldo() throws Exception {
        pagamentoConciliado(E2E, TXID, "100.00");
        devolver(E2E, "dev001", "40.00").andExpect(status().isCreated());

        resultado(E2E, "dev001", "DEVOLVIDO")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DEVOLVIDO"));

        assertThat(valorDevolvido(E2E)).isEqualByComparingTo("40.00");
        assertThat(statusCobranca(TXID)).isEqualTo("DEVOLVIDA_PARCIAL");
    }

    @Test
    @DisplayName("repetir o mesmo resultado é inofensivo: o estorno acontece uma única vez")
    void resultadoRepetidoEIdempotente() throws Exception {
        pagamentoConciliado(E2E, TXID, "100.00");
        devolver(E2E, "dev001", "30.00").andExpect(status().isCreated());
        devolver(E2E, "dev002", "30.00").andExpect(status().isCreated());

        resultado(E2E, "dev001", "NAO_REALIZADO").andExpect(status().isOk());
        resultado(E2E, "dev001", "NAO_REALIZADO").andExpect(status().isOk());

        assertThat(valorDevolvido(E2E)).isEqualByComparingTo("30.00");
        assertSaldoConsistente(E2E);
    }

    @Test
    @DisplayName("resultado contrário ao já registrado: 409 e o saldo não muda")
    void resultadoContrarioDa409() throws Exception {
        pagamentoConciliado(E2E, TXID, "100.00");
        devolver(E2E, "dev001", "30.00").andExpect(status().isCreated());
        devolver(E2E, "dev002", "30.00").andExpect(status().isCreated());
        resultado(E2E, "dev001", "NAO_REALIZADO").andExpect(status().isOk());
        resultado(E2E, "dev002", "DEVOLVIDO").andExpect(status().isOk());

        resultado(E2E, "dev001", "DEVOLVIDO").andExpect(status().isConflict());
        resultado(E2E, "dev002", "NAO_REALIZADO").andExpect(status().isConflict());

        assertThat(valorDevolvido(E2E)).isEqualByComparingTo("30.00");
        assertSaldoConsistente(E2E);
    }

    @Test
    @Timeout(60)
    @DisplayName("NAO_REALIZADO enviado 6 vezes ao mesmo tempo estorna o saldo uma única vez")
    void estornosConcorrentesDebitamUmaVez() throws Exception {
        pagamentoConciliado(E2E, TXID, "100.00");
        devolver(E2E, "dev001", "30.00").andExpect(status().isCreated());
        devolver(E2E, "dev002", "30.00").andExpect(status().isCreated());

        List<Integer> statuses = emParalelo(6, i -> resultado(E2E, "dev001", "NAO_REALIZADO"));

        assertThat(quantos(statuses, 200)).isEqualTo(6);
        assertThat(valorDevolvido(E2E)).isEqualByComparingTo("30.00");
        assertSaldoConsistente(E2E);
    }

    @Test
    @DisplayName("resultado EM_PROCESSAMENTO ou ausente: 400; devolução inexistente: 404")
    void resultadoInvalido() throws Exception {
        pagamentoConciliado(E2E, TXID, "100.00");
        devolver(E2E, "dev001", "30.00").andExpect(status().isCreated());

        resultado(E2E, "dev001", "EM_PROCESSAMENTO").andExpect(status().isBadRequest());
        mockMvc.perform(put("/api/v1/pagamentos/{e2e}/devolucoes/{id}/resultado", E2E, "dev001")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        resultado(E2E, "naoexiste", "DEVOLVIDO").andExpect(status().isNotFound());
        assertThat(valorDevolvido(E2E)).isEqualByComparingTo("30.00");
    }
}
