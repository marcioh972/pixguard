package br.com.pixguard.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.pixguard.config.WebhookProperties;
import br.com.pixguard.domain.Cobranca;
import br.com.pixguard.domain.TipoCobranca;
import br.com.pixguard.service.CobrancaService;
import br.com.pixguard.service.PagamentoService;
import br.com.pixguard.support.IntegracaoBase;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * O retry funcionando na pilha REAL (controller, WebhookService, proxy transacional, PostgreSQL).
 * O PagamentoService é um spy que simula o conflito de @Version nas primeiras chamadas e depois executa o
 * código verdadeiro. É determinístico, ao contrário de depender de uma corrida acontecer.
 */
@DisplayName("Webhook: retentativa de conflito de concorrência (pilha real)")
class WebhookRetentativaTest extends IntegracaoBase {

    private static final String TXID = "a1b2c3d4e5f60718293a4b5c6d7e8f90";
    private static final String E2E = "E00000000202611051200abcdef00001";
    private static final String WEBHOOK = "{\"pix\":[{\"endToEndId\":\"" + E2E + "\",\"txid\":\"" + TXID
            + "\",\"valor\":\"110.00\",\"horario\":\"2026-11-05T12:05:00Z\"}]}";

    @MockitoSpyBean
    private PagamentoService pagamentoService;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CobrancaService cobrancaService;

    @Autowired
    private WebhookProperties propriedades;

    private ResultActions enviar() throws Exception {
        return mockMvc.perform(post("/api/v1/webhooks/pix").contentType(MediaType.APPLICATION_JSON).content(WEBHOOK));
    }

    private static ObjectOptimisticLockingFailureException conflito() {
        return new ObjectOptimisticLockingFailureException(Cobranca.class, 1L);
    }

    private void criarCobranca() {
        txids.proximo(TXID);
        cobrancaService.criar(TipoCobranca.IMEDIATA, new BigDecimal("110.00"), null);
    }

    private String statusPagamento() {
        return jdbc.queryForObject("SELECT status FROM pagamento WHERE end_to_end_id = ?", String.class, E2E);
    }

    @Test
    @DisplayName("conflito na primeira tentativa é absorvido: 200 e o pagamento é baixado uma única vez")
    void conflitoNaPrimeiraTentativaEAbsorvido() throws Exception {
        criarCobranca();
        doThrow(conflito()).doCallRealMethod().when(pagamentoService).processar(any());

        enviar().andExpect(status().isOk());

        verify(pagamentoService, times(2)).processar(any());
        assertThat(statusPagamento()).isEqualTo("CONCILIADO");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pagamento", Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT status FROM cobranca WHERE txid = ?", String.class, TXID))
                .isEqualTo("PAGA");
    }

    @Test
    @DisplayName("o número máximo de retentativas consecutivas ainda é absorvido")
    void ultimaRetentativaAindaFunciona() throws Exception {
        criarCobranca();
        var stub = doThrow(conflito());
        for (int i = 1; i < propriedades.maxRetentativas(); i++) {
            stub = stub.doThrow(conflito());
        }
        stub.doCallRealMethod().when(pagamentoService).processar(any());

        enviar().andExpect(status().isOk());

        verify(pagamentoService, times(1 + propriedades.maxRetentativas())).processar(any());
        assertThat(statusPagamento()).isEqualTo("CONCILIADO");
    }

    @Test
    @DisplayName("retentativas esgotadas: 503, nada é gravado; o reenvio do banco depois processa normalmente")
    void retentativasEsgotadasDevolve503EReenvioFunciona() throws Exception {
        criarCobranca();
        doThrow(conflito()).when(pagamentoService).processar(any());

        enviar().andExpect(status().isServiceUnavailable());

        verify(pagamentoService, times(1 + propriedades.maxRetentativas())).processar(any());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pagamento", Long.class)).isZero();

        // O banco reenvia o webhook (5xx); desta vez não há conflito.
        doCallRealMethod().when(pagamentoService).processar(any());
        enviar().andExpect(status().isOk());

        assertThat(statusPagamento()).isEqualTo("CONCILIADO");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pagamento", Long.class)).isEqualTo(1L);
    }

    @Test
    @DisplayName("erro que não é de concorrência não é repetido: uma única chamada e 503")
    void erroComumNaoERepetido() throws Exception {
        criarCobranca();
        doThrow(new IllegalStateException("bug")).when(pagamentoService).processar(any());

        enviar().andExpect(status().isServiceUnavailable());

        verify(pagamentoService, times(1)).processar(any());
    }
}
