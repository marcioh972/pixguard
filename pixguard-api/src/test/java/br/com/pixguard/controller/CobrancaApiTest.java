package br.com.pixguard.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.pixguard.support.IntegracaoBase;
import com.jayway.jsonpath.JsonPath;
import java.math.BigDecimal;
import java.time.Instant;
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

@DisplayName("API de cobranças (POST e GET /api/v1/cobrancas)")
class CobrancaApiTest extends IntegracaoBase {

    @Autowired
    private MockMvc mockMvc;

    private ResultActions postar(String json) throws Exception {
        return mockMvc.perform(post("/api/v1/cobrancas")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json));
    }

    private long totalCobrancas() {
        Long total = jdbc.queryForObject("SELECT count(*) FROM cobranca", Long.class);
        return total == null ? 0 : total;
    }

    @Test
    @DisplayName("cria cobrança imediata: 201, Location, expiração = agora + prazo padrão e persistida")
    void criaImediata() throws Exception {
        String corpo = postar("""
                {"tipo":"IMEDIATA","valor":"110.00"}
                """)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.tipo").value("IMEDIATA"))
                .andExpect(jsonPath("$.status").value("ATIVA"))
                .andExpect(jsonPath("$.valor").value("110.00"))
                .andExpect(jsonPath("$.txid").value(matchesPattern("^[a-zA-Z0-9]{32}$")))
                .andExpect(jsonPath("$.criadaEm").value("2026-11-05T12:00:00Z"))
                .andExpect(jsonPath("$.expiraEm").value("2026-11-05T13:00:00Z"))
                .andExpect(jsonPath("$.dataVencimento").doesNotExist())
                .andReturn().getResponse().getContentAsString();

        String txid = JsonPath.read(corpo, "$.txid");
        assertThat(totalCobrancas()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT txid FROM cobranca", String.class)).isEqualTo(txid);
        assertThat(jdbc.queryForObject("SELECT valor FROM cobranca", BigDecimal.class))
                .isEqualByComparingTo("110.00");
    }

    @Test
    @DisplayName("resposta 201 traz Location apontando para a própria cobrança")
    void devolveLocation() throws Exception {
        var resultado = postar("""
                {"tipo":"IMEDIATA","valor":"10.00"}
                """).andExpect(status().isCreated()).andReturn();

        String txid = JsonPath.read(resultado.getResponse().getContentAsString(), "$.txid");
        assertThat(resultado.getResponse().getHeader("Location")).endsWith("/api/v1/cobrancas/" + txid);
    }

    @Test
    @DisplayName("cria cobrança com vencimento: expira no fim do dia do vencimento + 30 dias (fuso São Paulo)")
    void criaComVencimento() throws Exception {
        // vencimento 10/11 + 30 dias => expira à 00:00 de 11/12 em São Paulo (UTC-3) = 03:00Z
        postar("""
                {"tipo":"COM_VENCIMENTO","valor":"50.00","dataVencimento":"2026-11-10"}
                """)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.tipo").value("COM_VENCIMENTO"))
                .andExpect(jsonPath("$.dataVencimento").value("2026-11-10"))
                .andExpect(jsonPath("$.expiraEm").value("2026-12-11T03:00:00Z"));

        assertThat(totalCobrancas()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT data_vencimento::text FROM cobranca", String.class))
                .isEqualTo("2026-11-10");
    }

    @Test
    @DisplayName("vencimento no dia de hoje em São Paulo é aceito mesmo já sendo o dia seguinte em UTC")
    void vencimentoDeHojeNoFusoDeSaoPauloEAceito() throws Exception {
        // 01:00Z de 11/11 ainda é 22:00 de 10/11 em São Paulo
        relogio.definir(Instant.parse("2026-11-11T01:00:00Z"));

        postar("""
                {"tipo":"COM_VENCIMENTO","valor":"50.00","dataVencimento":"2026-11-10"}
                """)
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("vencimento no passado: 422 e nada é gravado")
    void vencimentoNoPassado() throws Exception {
        postar("""
                {"tipo":"COM_VENCIMENTO","valor":"50.00","dataVencimento":"2026-11-04"}
                """)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(422));

        assertThat(totalCobrancas()).isZero();
    }

    static Stream<Arguments> payloadsInvalidos() {
        return Stream.of(
                Arguments.of("valor sem casas decimais", "{\"tipo\":\"IMEDIATA\",\"valor\":\"110\"}"),
                Arguments.of("valor com 1 casa decimal", "{\"tipo\":\"IMEDIATA\",\"valor\":\"110.5\"}"),
                Arguments.of("valor com 3 casas decimais", "{\"tipo\":\"IMEDIATA\",\"valor\":\"10.001\"}"),
                Arguments.of("valor zero", "{\"tipo\":\"IMEDIATA\",\"valor\":\"0.00\"}"),
                Arguments.of("valor negativo", "{\"tipo\":\"IMEDIATA\",\"valor\":\"-1.00\"}"),
                Arguments.of("valor com vírgula", "{\"tipo\":\"IMEDIATA\",\"valor\":\"110,00\"}"),
                Arguments.of("valor em notação científica", "{\"tipo\":\"IMEDIATA\",\"valor\":\"1e2\"}"),
                Arguments.of("valor ausente", "{\"tipo\":\"IMEDIATA\"}"),
                Arguments.of("tipo ausente", "{\"valor\":\"10.00\"}"),
                Arguments.of("tipo desconhecido", "{\"tipo\":\"RECORRENTE\",\"valor\":\"10.00\"}"),
                Arguments.of("IMEDIATA com dataVencimento",
                        "{\"tipo\":\"IMEDIATA\",\"valor\":\"10.00\",\"dataVencimento\":\"2026-12-10\"}"),
                Arguments.of("COM_VENCIMENTO sem dataVencimento",
                        "{\"tipo\":\"COM_VENCIMENTO\",\"valor\":\"10.00\"}"),
                Arguments.of("dataVencimento em formato inválido",
                        "{\"tipo\":\"COM_VENCIMENTO\",\"valor\":\"10.00\",\"dataVencimento\":\"10/12/2026\"}"),
                Arguments.of("JSON malformado", "{tipo"));
    }

    @ParameterizedTest(name = "{0}: 400 e nada é gravado")
    @MethodSource("payloadsInvalidos")
    @DisplayName("payload inválido devolve 400 em ProblemDetail e não grava nada")
    void payloadInvalido(String descricao, String json) throws Exception {
        postar(json)
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(400));

        assertThat(totalCobrancas()).isZero();
    }

    @Test
    @DisplayName("400 de validação lista os campos com erro")
    void validacaoListaCampos() throws Exception {
        postar("""
                {"tipo":"IMEDIATA","valor":"110"}
                """)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.erros[0].campo").value("valor"));
    }

    @Test
    @DisplayName("consulta cobrança existente pelo txid")
    void buscaExistente() throws Exception {
        String corpo = postar("""
                {"tipo":"IMEDIATA","valor":"110.00"}
                """).andReturn().getResponse().getContentAsString();
        String txid = JsonPath.read(corpo, "$.txid");

        mockMvc.perform(get("/api/v1/cobrancas/{txid}", txid))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.txid").value(txid))
                .andExpect(jsonPath("$.status").value("ATIVA"))
                .andExpect(jsonPath("$.valor").value("110.00"));
    }

    @Test
    @DisplayName("consulta de txid inexistente: 404 em ProblemDetail")
    void buscaInexistente() throws Exception {
        mockMvc.perform(get("/api/v1/cobrancas/{txid}", "naoexiste0000000000000000000000"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(404));
    }
}
