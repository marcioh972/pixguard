package br.com.pixguard.mockbank;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * O banco simulado inteiro: os endpoints e os quatro modos de entrega do webhook. O "webhook da API"
 * é um servidor HTTP do JDK que guarda o que recebe; assim o teste vê exatamente o que iria pela rede.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("mock-bank (POST /mock/pix/pagar e GET /mock/cobrancas/{txid})")
class MockBankApiTest {

    private static final String TXID = "a1b2c3d4e5f60718293a4b5c6d7e8f90";
    private static final String E2E = "E00000000202611051200abcdef00001";

    private static final List<String> RECEBIDOS = new CopyOnWriteArrayList<>();
    private static final HttpServer RECEPTOR = subirReceptor();

    private static HttpServer subirReceptor() {
        try {
            HttpServer servidor = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            servidor.createContext("/webhook", troca -> {
                RECEBIDOS.add(new String(troca.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                troca.sendResponseHeaders(200, -1);
                troca.close();
            });
            servidor.start();
            return servidor;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @DynamicPropertySource
    static void propriedades(DynamicPropertyRegistry registro) {
        registro.add("mockbank.webhook", () -> "http://localhost:" + RECEPTOR.getAddress().getPort() + "/webhook");
    }

    @Autowired
    private MockMvc mockMvc;

    @BeforeEach
    void limpar() throws Exception {
        RECEBIDOS.clear();
        mockMvc.perform(delete("/mock/pagamentos")).andExpect(status().isNoContent());
    }

    private ResultActions pagar(String json) throws Exception {
        return mockMvc.perform(post("/mock/pix/pagar").contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private static String endToEndIdDe(String webhook) {
        return JsonPath.read(webhook, "$.pix[0].endToEndId");
    }

    private static Instant horarioDe(String webhook) {
        return Instant.parse(JsonPath.read(webhook, "$.pix[0].horario"));
    }

    @Test
    @DisplayName("NORMAL: um webhook no formato do BCB e a consulta passa a mostrar CONCLUIDA")
    void normal() throws Exception {
        pagar("{\"txid\":\"%s\",\"valor\":\"110.00\",\"endToEndId\":\"%s\",\"modo\":\"NORMAL\"}".formatted(TXID, E2E))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entregas[0].resultado").value("HTTP 200"));

        assertThat(RECEBIDOS).hasSize(1);
        String webhook = RECEBIDOS.get(0);
        assertThat((String) JsonPath.read(webhook, "$.pix[0].endToEndId")).isEqualTo(E2E);
        assertThat((String) JsonPath.read(webhook, "$.pix[0].txid")).isEqualTo(TXID);
        assertThat((String) JsonPath.read(webhook, "$.pix[0].valor")).isEqualTo("110.00");
        assertThat(horarioDe(webhook)).isNotNull();

        mockMvc.perform(get("/mock/cobrancas/{txid}", TXID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONCLUIDA"))
                .andExpect(jsonPath("$.pix.endToEndId").value(E2E))
                .andExpect(jsonPath("$.pix.valor").value("110.00"));
    }

    @Test
    @DisplayName("cobrança sem pagamento registrado está ATIVA")
    void semPagamentoEstaAtiva() throws Exception {
        mockMvc.perform(get("/mock/cobrancas/{txid}", TXID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ATIVA"))
                .andExpect(jsonPath("$.pix").doesNotExist());
    }

    @Test
    @DisplayName("DUPLICADO: o MESMO webhook (mesmo endToEndId) é reenviado 'copias' vezes")
    void duplicado() throws Exception {
        pagar("{\"txid\":\"%s\",\"valor\":\"110.00\",\"endToEndId\":\"%s\",\"modo\":\"DUPLICADO\",\"copias\":3}"
                .formatted(TXID, E2E))
                .andExpect(status().isOk());

        assertThat(RECEBIDOS).hasSize(3);
        assertThat(RECEBIDOS).containsOnly(RECEBIDOS.get(0));
        assertThat(endToEndIdDe(RECEBIDOS.get(0))).isEqualTo(E2E);
    }

    @Test
    @DisplayName("FORA_DE_ORDEM: pagamentos distintos entregues do mais recente para o mais antigo")
    void foraDeOrdem() throws Exception {
        pagar("{\"txid\":\"%s\",\"valor\":\"110.00\",\"endToEndId\":\"%s\",\"modo\":\"FORA_DE_ORDEM\",\"copias\":3}"
                .formatted(TXID, E2E))
                .andExpect(status().isOk());

        assertThat(RECEBIDOS).hasSize(3);
        List<String> ids = RECEBIDOS.stream().map(MockBankApiTest::endToEndIdDe).toList();
        assertThat(ids).doesNotHaveDuplicates();
        assertThat(horarioDe(RECEBIDOS.get(0))).isAfter(horarioDe(RECEBIDOS.get(1)));
        assertThat(horarioDe(RECEBIDOS.get(1))).isAfter(horarioDe(RECEBIDOS.get(2)));
        // O mais antigo é o último a chegar e é o que usa o endToEndId pedido.
        assertThat(ids.get(2)).isEqualTo(E2E);

        // A consulta mostra o pagamento mais antigo, qualquer que tenha sido a ordem de entrega.
        mockMvc.perform(get("/mock/cobrancas/{txid}", TXID))
                .andExpect(jsonPath("$.pix.endToEndId").value(E2E));
    }

    @Test
    @DisplayName("ATRASADO: o banco já mostra CONCLUIDA, mas o webhook só chega depois do atraso")
    void atrasado() throws Exception {
        pagar("{\"txid\":\"%s\",\"valor\":\"110.00\",\"endToEndId\":\"%s\",\"modo\":\"ATRASADO\",\"atrasoMs\":700}"
                .formatted(TXID, E2E))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.agendado").value(true));

        assertThat(RECEBIDOS).as("webhook ainda não saiu").isEmpty();
        mockMvc.perform(get("/mock/cobrancas/{txid}", TXID))
                .andExpect(jsonPath("$.status").value("CONCLUIDA"));

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(RECEBIDOS).hasSize(1));
        assertThat(endToEndIdDe(RECEBIDOS.get(0))).isEqualTo(E2E);
    }

    @Test
    @DisplayName("sem endToEndId o banco gera um válido (32 caracteres alfanuméricos)")
    void geraEndToEndId() throws Exception {
        pagar("{\"txid\":\"%s\",\"valor\":\"110.00\",\"modo\":\"NORMAL\"}".formatted(TXID))
                .andExpect(status().isOk());

        assertThat(endToEndIdDe(RECEBIDOS.get(0))).matches("^[a-zA-Z0-9]{32}$");
    }

    @Test
    @DisplayName("pedido inválido: 400 e nenhum webhook enviado")
    void pedidoInvalido() throws Exception {
        pagar("{\"txid\":\"curto\",\"valor\":\"110.00\",\"modo\":\"NORMAL\"}").andExpect(status().isBadRequest());
        pagar("{\"txid\":\"%s\",\"valor\":\"110\",\"modo\":\"NORMAL\"}".formatted(TXID)).andExpect(status().isBadRequest());
        pagar("{\"txid\":\"%s\",\"valor\":\"110.00\",\"modo\":\"INEXISTENTE\"}".formatted(TXID))
                .andExpect(status().isBadRequest());
        pagar("{\"txid\":\"%s\",\"valor\":\"110.00\"}".formatted(TXID)).andExpect(status().isBadRequest());
        pagar("{\"txid\":\"%s\",\"valor\":\"110.00\",\"modo\":\"DUPLICADO\",\"copias\":0}".formatted(TXID))
                .andExpect(status().isBadRequest());

        assertThat(RECEBIDOS).isEmpty();
    }
}
