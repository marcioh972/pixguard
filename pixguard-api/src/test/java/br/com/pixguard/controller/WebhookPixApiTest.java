package br.com.pixguard.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.pixguard.support.IntegracaoBase;
import java.time.Duration;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@DisplayName("Webhook Pix (POST /api/v1/webhooks/pix)")
class WebhookPixApiTest extends IntegracaoBase {

    private static final String TXID = "a1b2c3d4e5f60718293a4b5c6d7e8f90";
    private static final String TXID_2 = "b2c3d4e5f60718293a4b5c6d7e8f9012a";
    private static final String E2E_1 = "E00000000202611051200abcdef00001";
    private static final String E2E_2 = "E00000000202611051200abcdef00002";

    // Relógio inicial = 2026-11-05T12:00:00Z; cobrança imediata criada nele expira às 13:00:00Z.
    private static final String PAGO_12_02 = "2026-11-05T12:02:00Z";
    private static final String PAGO_12_04 = "2026-11-05T12:04:00Z";
    private static final String PAGO_12_05 = "2026-11-05T12:05:00Z";
    private static final String PAGO_12_06 = "2026-11-05T12:06:00Z";
    private static final String PAGO_12_30 = "2026-11-05T12:30:00Z";
    private static final String PAGO_13_30 = "2026-11-05T13:30:00Z";

    @Autowired
    private MockMvc mockMvc;

    // ---------- helpers ----------

    private static String item(String e2e, String txid, String valor, String horario) {
        return "{\"endToEndId\":\"%s\",\"txid\":\"%s\",\"valor\":\"%s\",\"horario\":\"%s\"}"
                .formatted(e2e, txid, valor, horario);
    }

    private static String payload(String... itens) {
        return "{\"pix\":[" + String.join(",", itens) + "]}";
    }

    private ResultActions enviar(String json) throws Exception {
        return mockMvc.perform(post("/api/v1/webhooks/pix")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json));
    }

    private void receber(String... itens) throws Exception {
        enviar(payload(itens)).andExpect(status().isOk());
    }

    private void criarCobranca(String txid) throws Exception {
        txids.proximo(txid);
        mockMvc.perform(post("/api/v1/cobrancas")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tipo\":\"IMEDIATA\",\"valor\":\"110.00\"}"))
                .andExpect(status().isCreated());
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

    private Long cobrancaIdDoPagamento(String e2e) {
        return jdbc.queryForObject("SELECT cobranca_id FROM pagamento WHERE end_to_end_id = ?", Long.class, e2e);
    }

    private Long idDaCobranca(String txid) {
        return jdbc.queryForObject("SELECT id FROM cobranca WHERE txid = ?", Long.class, txid);
    }

    /** Simula o job (etapa 4) tendo expirado a cobrança antes de um webhook atrasado chegar. */
    private void expirarCobrancaNoBanco(String txid) {
        jdbc.update("UPDATE cobranca SET status = 'EXPIRADA' WHERE txid = ?", txid);
        relogio.avancar(Duration.ofHours(2));
    }

    // ---------- caminho feliz ----------

    @Test
    @DisplayName("pagamento dentro do prazo baixa a cobrança e fica CONCILIADO")
    void pagamentoConciliado() throws Exception {
        criarCobranca(TXID);

        receber(item(E2E_1, TXID, "110.00", PAGO_12_05));

        assertThat(statusPagamento(E2E_1)).isEqualTo("CONCILIADO");
        assertThat(statusCobranca(TXID)).isEqualTo("PAGA");
        assertThat(cobrancaIdDoPagamento(E2E_1)).isEqualTo(idDaCobranca(TXID));
    }

    // ---------- duplicado ----------

    @Test
    @DisplayName("pagamentoDuplicado: o mesmo webhook duas vezes baixa uma única vez e responde 200 nas duas")
    void pagamentoDuplicado() throws Exception {
        criarCobranca(TXID);
        String mesmoItem = item(E2E_1, TXID, "110.00", PAGO_12_05);

        receber(mesmoItem);
        long versaoAposPrimeiro = contar("SELECT version FROM cobranca WHERE txid = ?", TXID);
        receber(mesmoItem);

        assertThat(contar("SELECT count(*) FROM pagamento")).isEqualTo(1);
        assertThat(contar("SELECT count(*) FROM pagamento WHERE status = 'CONCILIADO'")).isEqualTo(1);
        assertThat(statusCobranca(TXID)).isEqualTo("PAGA");
        // A segunda chamada não tocou na cobrança: a version é a mesma de depois da primeira baixa.
        assertThat(contar("SELECT version FROM cobranca WHERE txid = ?", TXID)).isEqualTo(versaoAposPrimeiro);
    }

    @Test
    @DisplayName("itens independentes: duplicado e novo na mesma requisição, o novo é processado")
    void itensIndependentes() throws Exception {
        criarCobranca(TXID);
        receber(item(E2E_1, TXID, "110.00", PAGO_12_05));

        receber(item(E2E_1, TXID, "110.00", PAGO_12_05),
                item(E2E_2, TXID_2, "50.00", PAGO_12_05));

        assertThat(contar("SELECT count(*) FROM pagamento")).isEqualTo(2);
        assertThat(statusPagamento(E2E_2)).isEqualTo("AGUARDANDO_COBRANCA");
    }

    // ---------- expirado ----------

    @Test
    @DisplayName("pagamentoExpirado: pago depois de expira_em vira RECEBIDO_APOS_EXPIRACAO e a cobrança segue EXPIRADA")
    void pagamentoExpirado() throws Exception {
        criarCobranca(TXID);
        expirarCobrancaNoBanco(TXID);

        receber(item(E2E_1, TXID, "110.00", PAGO_13_30));

        assertThat(statusPagamento(E2E_1)).isEqualTo("RECEBIDO_APOS_EXPIRACAO");
        assertThat(statusCobranca(TXID)).isEqualTo("EXPIRADA");
        assertThat(cobrancaIdDoPagamento(E2E_1)).isEqualTo(idDaCobranca(TXID));
        assertThat(contar("SELECT count(*) FROM pagamento WHERE status = 'CONCILIADO'")).isZero();
    }

    @Test
    @DisplayName("pago após expira_em com a cobrança ainda ATIVA (job não rodou): não baixa, a cobrança não muda")
    void pagamentoAposPrazoComCobrancaAindaAtiva() throws Exception {
        criarCobranca(TXID);
        relogio.avancar(Duration.ofHours(2));

        receber(item(E2E_1, TXID, "110.00", PAGO_13_30));

        assertThat(statusPagamento(E2E_1)).isEqualTo("RECEBIDO_APOS_EXPIRACAO");
        assertThat(statusCobranca(TXID)).isEqualTo("ATIVA");
    }

    @Test
    @DisplayName("webhook atrasado: cobrança já EXPIRADA pelo job, mas pago dentro do prazo, é baixada")
    void webhookAtrasadoDentroDoPrazo() throws Exception {
        criarCobranca(TXID);
        expirarCobrancaNoBanco(TXID);

        // O relógio já passou das 13:00, mas o banco diz que o pagamento foi às 12:30: vale o horário do pagamento.
        receber(item(E2E_1, TXID, "110.00", PAGO_12_30));

        assertThat(statusPagamento(E2E_1)).isEqualTo("CONCILIADO");
        assertThat(statusCobranca(TXID)).isEqualTo("PAGA");
    }

    // ---------- antes da cobrança ----------

    @Test
    @DisplayName("webhookAntesDaCobranca: fica AGUARDANDO_COBRANCA e é conciliado quando a cobrança é criada")
    void webhookAntesDaCobranca() throws Exception {
        relogio.definir(java.time.Instant.parse("2026-11-05T12:05:00Z"));
        receber(item(E2E_1, TXID, "110.00", PAGO_12_04));

        assertThat(statusPagamento(E2E_1)).isEqualTo("AGUARDANDO_COBRANCA");
        assertThat(cobrancaIdDoPagamento(E2E_1)).isNull();

        relogio.definir(java.time.Instant.parse("2026-11-05T12:10:00Z"));
        criarCobranca(TXID);

        assertThat(statusPagamento(E2E_1)).isEqualTo("CONCILIADO");
        assertThat(statusCobranca(TXID)).isEqualTo("PAGA");
        assertThat(cobrancaIdDoPagamento(E2E_1)).isEqualTo(idDaCobranca(TXID));
    }

    @Test
    @DisplayName("webhook sem cobrança correspondente nunca é descartado: fica registrado e aguardando")
    void semCobrancaNaoEDescartado() throws Exception {
        receber(item(E2E_1, TXID, "110.00", PAGO_12_05));

        assertThat(contar("SELECT count(*) FROM pagamento")).isEqualTo(1);
        assertThat(statusPagamento(E2E_1)).isEqualTo("AGUARDANDO_COBRANCA");
    }

    @Test
    @DisplayName("vários pagamentos esperando: ao criar a cobrança, o mais ANTIGO pelo horário do banco é baixado")
    void variosAguardandoOMaisAntigoVence() throws Exception {
        // E2E_1 chega primeiro (12:04), mas E2E_2 foi pago antes (12:02): E2E_2 é o válido.
        receber(item(E2E_1, TXID, "110.00", PAGO_12_04));
        receber(item(E2E_2, TXID, "110.00", PAGO_12_02));

        criarCobranca(TXID);

        assertThat(statusPagamento(E2E_2)).isEqualTo("CONCILIADO");
        assertThat(statusPagamento(E2E_1)).isEqualTo("EXCEDENTE");
        assertThat(statusCobranca(TXID)).isEqualTo("PAGA");
    }

    // ---------- excedente ----------

    @Test
    @DisplayName("pagamentoExcedente: segundo endToEndId para cobrança já paga vira EXCEDENTE, sem segunda baixa")
    void pagamentoExcedente() throws Exception {
        criarCobranca(TXID);
        receber(item(E2E_1, TXID, "110.00", PAGO_12_05));

        receber(item(E2E_2, TXID, "110.00", PAGO_12_06));

        assertThat(statusPagamento(E2E_1)).isEqualTo("CONCILIADO");
        assertThat(statusPagamento(E2E_2)).isEqualTo("EXCEDENTE");
        assertThat(cobrancaIdDoPagamento(E2E_2)).isEqualTo(idDaCobranca(TXID));
        assertThat(statusCobranca(TXID)).isEqualTo("PAGA");
        assertThat(contar("SELECT count(*) FROM pagamento WHERE status = 'CONCILIADO'")).isEqualTo(1);
        assertThat(contar("SELECT count(*) FROM pagamento")).isEqualTo(2);
    }

    // ---------- fora de ordem ----------

    @Test
    @DisplayName("webhookForaDeOrdem: o pagamento tardio chega DEPOIS do que foi pago após o prazo e ainda assim é baixado")
    void webhookForaDeOrdemNaoPerdeOPagamentoValido() throws Exception {
        criarCobranca(TXID);                                  // expira às 13:00Z
        relogio.definir(java.time.Instant.parse("2026-11-05T13:35:00Z"));
        // Pago às 13:30 (fora do prazo) chega primeiro...
        receber(item(E2E_2, TXID, "110.00", PAGO_13_30));
        assertThat(statusPagamento(E2E_2)).isEqualTo("RECEBIDO_APOS_EXPIRACAO");
        assertThat(statusCobranca(TXID)).isEqualTo("ATIVA");

        relogio.definir(java.time.Instant.parse("2026-11-05T13:40:00Z"));
        // ...e só depois chega o pago às 12:30 (dentro do prazo).
        receber(item(E2E_1, TXID, "110.00", PAGO_12_30));

        assertThat(statusPagamento(E2E_1)).isEqualTo("CONCILIADO");
        assertThat(statusPagamento(E2E_2)).isEqualTo("RECEBIDO_APOS_EXPIRACAO");
        assertThat(statusCobranca(TXID)).isEqualTo("PAGA");
        assertThat(contar("SELECT count(*) FROM pagamento WHERE status = 'CONCILIADO'")).isEqualTo(1);
    }

    @Test
    @DisplayName("webhook fora de ordem com a cobrança já criada: o primeiro processado vence, o outro vira EXCEDENTE")
    void webhookForaDeOrdemPrimeiroProcessadoVence() throws Exception {
        criarCobranca(TXID);

        // E2E_2 foi pago às 12:30, E2E_1 às 12:05, mas E2E_2 chega antes.
        receber(item(E2E_2, TXID, "110.00", PAGO_12_30));
        receber(item(E2E_1, TXID, "110.00", PAGO_12_05));

        assertThat(statusPagamento(E2E_2)).isEqualTo("CONCILIADO");
        assertThat(statusPagamento(E2E_1)).isEqualTo("EXCEDENTE");
        assertThat(contar("SELECT count(*) FROM pagamento WHERE status = 'CONCILIADO'")).isEqualTo(1);
    }

    // ---------- payload inválido ----------

    static Stream<Arguments> payloadsInvalidos() {
        String valido = item(E2E_1, TXID, "110.00", PAGO_12_05);
        return Stream.of(
                Arguments.of("pix ausente", "{}"),
                Arguments.of("pix vazio", "{\"pix\":[]}"),
                Arguments.of("item nulo", "{\"pix\":[null]}"),
                Arguments.of("endToEndId com 31 caracteres", payload(item(E2E_1.substring(1), TXID, "110.00", PAGO_12_05))),
                Arguments.of("txid fora do formato", payload(item(E2E_1, "curto", "110.00", PAGO_12_05))),
                Arguments.of("valor sem casas decimais", payload(item(E2E_1, TXID, "110", PAGO_12_05))),
                Arguments.of("valor zero", payload(item(E2E_1, TXID, "0.00", PAGO_12_05))),
                Arguments.of("horario inválido", payload(item(E2E_1, TXID, "110.00", "ontem"))),
                Arguments.of("horario ausente",
                        "{\"pix\":[{\"endToEndId\":\"" + E2E_1 + "\",\"txid\":\"" + TXID + "\",\"valor\":\"110.00\"}]}"),
                Arguments.of("JSON malformado", "{pix"),
                Arguments.of("um item válido e um inválido: nada é processado",
                        payload(valido, item(E2E_2, TXID, "110", PAGO_12_05))));
    }

    @ParameterizedTest(name = "{0}: 400 e nada é gravado")
    @MethodSource("payloadsInvalidos")
    @DisplayName("payload malformado devolve 400 em ProblemDetail e não grava pagamento")
    void payloadInvalido(String descricao, String json) throws Exception {
        enviar(json)
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(400));

        assertThat(contar("SELECT count(*) FROM pagamento")).isZero();
    }
}
