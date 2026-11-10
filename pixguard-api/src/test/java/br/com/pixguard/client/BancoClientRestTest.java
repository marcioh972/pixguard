package br.com.pixguard.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.pixguard.exception.BancoIndisponivelException;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/** O cliente HTTP contra um servidor HTTP de verdade (JDK), sem Spring: mapeamento, erros e timeout. */
@DisplayName("BancoClientRest (servidor HTTP real, sem Spring)")
class BancoClientRestTest {

    private static final String TXID = "a1b2c3d4e5f60718293a4b5c6d7e8f90";
    private static final int TIMEOUT_LEITURA_MS = 300;

    private HttpServer servidor;
    private BancoClientRest cliente;

    @BeforeEach
    void subirServidor() throws IOException {
        servidor = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        servidor.start();
        cliente = clienteApontandoPara(servidor.getAddress().getPort());
    }

    @AfterEach
    void pararServidor() {
        servidor.stop(0);
    }

    private static BancoClientRest clienteApontandoPara(int porta) {
        SimpleClientHttpRequestFactory fabrica = new SimpleClientHttpRequestFactory();
        fabrica.setConnectTimeout(500);
        fabrica.setReadTimeout(TIMEOUT_LEITURA_MS);
        return new BancoClientRest(RestClient.builder()
                .baseUrl("http://localhost:" + porta)
                .requestFactory(fabrica)
                .build());
    }

    private void responder(int status, String corpo, long atrasoMs) {
        servidor.createContext("/mock/cobrancas/" + TXID, troca -> {
            try {
                if (atrasoMs > 0) {
                    Thread.sleep(atrasoMs);
                }
                byte[] bytes = corpo.getBytes(StandardCharsets.UTF_8);
                troca.getResponseHeaders().add("Content-Type", "application/json");
                troca.sendResponseHeaders(status, bytes.length);
                try (OutputStream saida = troca.getResponseBody()) {
                    saida.write(bytes);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                troca.close();
            }
        });
    }

    private void responder(int status, String corpo) {
        responder(status, corpo, 0);
    }

    @Test
    @DisplayName("status ATIVA vira cobrança não paga")
    void ativa() {
        responder(200, "{\"txid\":\"" + TXID + "\",\"status\":\"ATIVA\"}");

        ConsultaCobranca consulta = cliente.consultarCobranca(TXID);

        assertThat(consulta.paga()).isFalse();
        assertThat(consulta.pagamento()).isNull();
    }

    @Test
    @DisplayName("status CONCLUIDA vira cobrança paga com os dados do pix")
    void concluida() {
        responder(200, """
                {"txid":"%s","status":"CONCLUIDA",
                 "pix":{"endToEndId":"E00000000202611051200abcdef00001","valor":"110.00","horario":"2026-11-05T12:30:00Z"}}
                """.formatted(TXID));

        ConsultaCobranca consulta = cliente.consultarCobranca(TXID);

        assertThat(consulta.paga()).isTrue();
        assertThat(consulta.pagamento().endToEndId()).isEqualTo("E00000000202611051200abcdef00001");
        assertThat(consulta.pagamento().valor()).isEqualByComparingTo("110.00");
        assertThat(consulta.pagamento().horario()).isEqualTo(Instant.parse("2026-11-05T12:30:00Z"));
    }

    @Test
    @DisplayName("404 é falha, nunca 'não paga' (expirar com base num palpite seria pior que não mudar nada)")
    void naoEncontradoEFalha() {
        responder(404, "{}");

        assertThatThrownBy(() -> cliente.consultarCobranca(TXID)).isInstanceOf(BancoIndisponivelException.class);
    }

    @Test
    @DisplayName("500 do banco é falha")
    void erroDoServidorEFalha() {
        responder(500, "{}");

        assertThatThrownBy(() -> cliente.consultarCobranca(TXID)).isInstanceOf(BancoIndisponivelException.class);
    }

    @Test
    @DisplayName("corpo que não é JSON é falha")
    void corpoInvalidoEFalha() {
        responder(200, "isto não é json");

        assertThatThrownBy(() -> cliente.consultarCobranca(TXID)).isInstanceOf(BancoIndisponivelException.class);
    }

    @Test
    @DisplayName("status desconhecido é falha")
    void statusDesconhecidoEFalha() {
        responder(200, "{\"txid\":\"" + TXID + "\",\"status\":\"REMOVIDA\"}");

        assertThatThrownBy(() -> cliente.consultarCobranca(TXID)).isInstanceOf(BancoIndisponivelException.class);
    }

    @Test
    @DisplayName("CONCLUIDA sem os dados do pix é falha")
    void concluidaSemPixEFalha() {
        responder(200, "{\"txid\":\"" + TXID + "\",\"status\":\"CONCLUIDA\"}");

        assertThatThrownBy(() -> cliente.consultarCobranca(TXID)).isInstanceOf(BancoIndisponivelException.class);
    }

    @Test
    @DisplayName("valor inválido na resposta é falha")
    void valorInvalidoEFalha() {
        responder(200, """
                {"txid":"%s","status":"CONCLUIDA",
                 "pix":{"endToEndId":"E00000000202611051200abcdef00001","valor":"abc","horario":"2026-11-05T12:30:00Z"}}
                """.formatted(TXID));

        assertThatThrownBy(() -> cliente.consultarCobranca(TXID)).isInstanceOf(BancoIndisponivelException.class);
    }

    @Test
    @DisplayName("resposta de outro txid é falha")
    void outroTxidEFalha() {
        responder(200, "{\"txid\":\"zzzzzzzzzzzzzzzzzzzzzzzzzz\",\"status\":\"ATIVA\"}");

        assertThatThrownBy(() -> cliente.consultarCobranca(TXID)).isInstanceOf(BancoIndisponivelException.class);
    }

    @Test
    @Timeout(10)
    @DisplayName("banco lento: o timeout de leitura corta a espera e vira falha")
    void timeoutDeLeitura() {
        responder(200, "{\"txid\":\"" + TXID + "\",\"status\":\"ATIVA\"}", 3_000);

        assertThatThrownBy(() -> cliente.consultarCobranca(TXID)).isInstanceOf(BancoIndisponivelException.class);
    }

    @Test
    @DisplayName("banco fora do ar (conexão recusada) é falha")
    void conexaoRecusada() {
        int portaFechada = servidor.getAddress().getPort();
        servidor.stop(0);

        assertThatThrownBy(() -> clienteApontandoPara(portaFechada).consultarCobranca(TXID))
                .isInstanceOf(BancoIndisponivelException.class);
    }
}
