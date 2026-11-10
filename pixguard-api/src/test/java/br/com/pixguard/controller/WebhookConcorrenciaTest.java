package br.com.pixguard.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import br.com.pixguard.domain.TipoCobranca;
import br.com.pixguard.service.CobrancaService;
import br.com.pixguard.service.ReconciliacaoService;
import br.com.pixguard.support.IntegracaoBase;
import br.com.pixguard.support.Paralelo;
import br.com.pixguard.support.RelogioControlavel;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Concorrência de verdade: threads liberadas juntas por uma CyclicBarrier contra o PostgreSQL real.
 * As conferências são feitas com count(*) no BANCO, nunca só pela resposta HTTP.
 */
@DisplayName("Webhook: concorrência")
class WebhookConcorrenciaTest extends IntegracaoBase {

    private static final String TXID = "a1b2c3d4e5f60718293a4b5c6d7e8f90";
    private static final String E2E_BASE = "E00000000202611051200abcdef0000"; // 31 caracteres + 1 dígito
    private static final String PAGO_12_30 = "2026-11-05T12:30:00Z";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CobrancaService cobrancaService;

    @Autowired
    private ReconciliacaoService reconciliacao;

    private static String e2e(int indice) {
        return E2E_BASE + indice;
    }

    private static String payload(String e2e, String txid, String horario) {
        return "{\"pix\":[{\"endToEndId\":\"%s\",\"txid\":\"%s\",\"valor\":\"110.00\",\"horario\":\"%s\"}]}"
                .formatted(e2e, txid, horario);
    }

    private int postar(String json) throws Exception {
        return mockMvc.perform(post("/api/v1/webhooks/pix").contentType(MediaType.APPLICATION_JSON).content(json))
                .andReturn().getResponse().getStatus();
    }

    private void criarCobranca(String txid) {
        txids.proximo(txid);
        cobrancaService.criar(TipoCobranca.IMEDIATA, new BigDecimal("110.00"), null);
    }

    private long contar(String sql, Object... args) {
        Long n = jdbc.queryForObject(sql, Long.class, args);
        return n == null ? 0 : n;
    }

    private String statusCobranca(String txid) {
        return jdbc.queryForObject("SELECT status FROM cobranca WHERE txid = ?", String.class, txid);
    }

    @Test
    @Timeout(60)
    @DisplayName("concorrencia: 8 threads com o MESMO webhook, liberadas juntas: exatamente 1 baixa")
    void concorrencia() throws Exception {
        criarCobranca(TXID);
        String mesmoWebhook = payload(e2e(1), TXID, PAGO_12_30);
        Callable<Integer> chamada = () -> postar(mesmoWebhook);

        List<Integer> statuses = Paralelo.executarJuntas(Collections.nCopies(8, chamada));

        assertThat(statuses).as("todas respondem 200: processado ou duplicado").containsOnly(200);
        assertThat(contar("SELECT count(*) FROM pagamento")).as("uma única linha de pagamento").isEqualTo(1);
        assertThat(contar("SELECT count(*) FROM pagamento WHERE end_to_end_id = ?", e2e(1))).isEqualTo(1);
        assertThat(contar("SELECT count(*) FROM pagamento WHERE status = 'CONCILIADO'")).isEqualTo(1);
        assertThat(statusCobranca(TXID)).isEqualTo("PAGA");
        // criação (version 0) + UMA baixa (version 1): a cobrança foi alterada uma única vez
        assertThat(contar("SELECT version FROM cobranca WHERE txid = ?", TXID)).isEqualTo(1);
    }

    @Test
    @Timeout(60)
    @DisplayName("6 pagamentos DIFERENTES da mesma cobrança ao mesmo tempo: 1 CONCILIADO e 5 EXCEDENTE, todos com 200")
    void pagamentosDistintosDaMesmaCobrancaTemUmaUnicaBaixa() throws Exception {
        criarCobranca(TXID);
        List<Callable<Integer>> chamadas = new ArrayList<>();
        for (int i = 1; i <= 6; i++) {
            String json = payload(e2e(i), TXID, PAGO_12_30);
            chamadas.add(() -> postar(json));
        }

        List<Integer> statuses = Paralelo.executarJuntas(chamadas);

        // Os perdedores da disputa (conflito de @Version ou índice de baixa única) foram repetidos e viraram EXCEDENTE.
        assertThat(statuses).containsOnly(200);
        assertThat(contar("SELECT count(*) FROM pagamento")).isEqualTo(6);
        assertThat(contar("SELECT count(*) FROM pagamento WHERE status = 'CONCILIADO'")).isEqualTo(1);
        assertThat(contar("SELECT count(*) FROM pagamento WHERE status = 'EXCEDENTE'")).isEqualTo(5);
        assertThat(contar("SELECT count(*) FROM pagamento WHERE status = 'AGUARDANDO_COBRANCA'")).isZero();
        assertThat(statusCobranca(TXID)).isEqualTo("PAGA");
    }

    @Test
    @Timeout(120)
    @DisplayName("webhook e job ao mesmo tempo sobre a mesma cobrança vencida: o pagamento válido nunca se perde")
    void webhookEJobSimultaneosNuncaPerdemOPagamento() throws Exception {
        // Várias rodadas, cada uma com cobrança nova, para exercitar interleavings diferentes.
        for (int rodada = 1; rodada <= 5; rodada++) {
            String txid = "a1b2c3d4e5f60718293a4b5c6d7e8f9" + rodada;
            String e2e = e2e(rodada);
            relogio.definir(RelogioControlavel.INICIO_PADRAO);
            criarCobranca(txid);                       // expira às 13:00Z
            relogio.avancar(Duration.ofHours(2));      // 14:00Z: a cobrança já está vencida para o job

            // O banco (falso) diz que a cobrança NÃO foi paga: o job quer EXPIRAR; o webhook traz um pagamento
            // feito às 12:30Z, dentro do prazo. Qualquer ordem de execução precisa terminar em PAGA.
            Callable<Object> job = () -> reconciliacao.processarCobrancasSemRetorno();
            Callable<Object> webhook = () -> postar(payload(e2e, txid, PAGO_12_30));

            List<Object> resultados = Paralelo.executarJuntas(List.of(job, webhook));

            assertThat(resultados.get(1)).as("rodada %d: webhook responde 200", rodada).isEqualTo(200);
            assertThat(statusCobranca(txid)).as("rodada %d", rodada).isEqualTo("PAGA");
            assertThat(contar("SELECT count(*) FROM pagamento WHERE end_to_end_id = ? AND status = 'CONCILIADO'", e2e))
                    .as("rodada %d", rodada).isEqualTo(1);
            assertThat(contar("SELECT count(*) FROM pagamento WHERE txid = ?", txid)).isEqualTo(1);
        }
    }
}
