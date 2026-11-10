package br.com.pixguard.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.pixguard.exception.TransicaoInvalidaException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("Cobranca (domínio, sem Spring)")
class CobrancaTest {

    private static final String TXID = "a1b2c3d4e5f60718293a4b5c6d7e8f90";
    private static final Instant CRIADA = Instant.parse("2026-11-05T12:00:00Z");
    private static final Instant EXPIRA = CRIADA.plusSeconds(3600);
    private static final Instant AGORA = CRIADA.plusSeconds(60);

    private static Cobranca imediata() {
        return Cobranca.criarImediata(TXID, new BigDecimal("110.00"), CRIADA, EXPIRA);
    }

    @Nested
    @DisplayName("criação")
    class Criacao {

        @Test
        @DisplayName("cobrança imediata nasce ATIVA, sem vencimento e com atualizadaEm = criadaEm")
        void imediataNasceAtiva() {
            Cobranca c = imediata();

            assertThat(c.getStatus()).isEqualTo(StatusCobranca.ATIVA);
            assertThat(c.getTipo()).isEqualTo(TipoCobranca.IMEDIATA);
            assertThat(c.getDataVencimento()).isNull();
            assertThat(c.getAtualizadaEm()).isEqualTo(CRIADA);
        }

        @Test
        @DisplayName("cobrança com vencimento guarda a data de vencimento")
        void comVencimentoGuardaData() {
            LocalDate vencimento = LocalDate.of(2026, 12, 10);

            Cobranca c = Cobranca.criarComVencimento(TXID, new BigDecimal("50.00"), vencimento, CRIADA, EXPIRA);

            assertThat(c.getTipo()).isEqualTo(TipoCobranca.COM_VENCIMENTO);
            assertThat(c.getDataVencimento()).isEqualTo(vencimento);
        }

        @Test
        @DisplayName("cobrança com vencimento sem data é rejeitada")
        void comVencimentoExigeData() {
            assertThatThrownBy(() -> Cobranca.criarComVencimento(TXID, BigDecimal.TEN, null, CRIADA, EXPIRA))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("dataVencimento");
        }

        @Test
        @DisplayName("valor com 1 casa decimal é normalizado para escala 2")
        void normalizaEscala() {
            Cobranca c = Cobranca.criarImediata(TXID, new BigDecimal("110.5"), CRIADA, EXPIRA);

            assertThat(c.getValor()).isEqualTo(new BigDecimal("110.50"));
            assertThat(c.getValor().scale()).isEqualTo(2);
        }

        @Test
        @DisplayName("valor com mais de 2 casas decimais é rejeitado, nunca arredondado")
        void rejeitaMaisDeDuasCasas() {
            assertThatThrownBy(() -> Cobranca.criarImediata(TXID, new BigDecimal("10.001"), CRIADA, EXPIRA))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("2 casas");
        }

        @ParameterizedTest(name = "valor {0} é rejeitado")
        @ValueSource(strings = {"0", "0.00", "-1.00"})
        @DisplayName("valor zero ou negativo é rejeitado")
        void rejeitaValorNaoPositivo(String valor) {
            assertThatThrownBy(() -> Cobranca.criarImediata(TXID, new BigDecimal(valor), CRIADA, EXPIRA))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("maior que zero");
        }

        @ParameterizedTest(name = "txid \"{0}\" é rejeitado")
        @ValueSource(strings = {"curto", "123e4567-e89b-12d3-a456-426614174000", "abcdefghijklmnopqrstuvwxy_"})
        @DisplayName("txid fora do formato é rejeitado")
        void rejeitaTxidInvalido(String txid) {
            assertThatThrownBy(() -> Cobranca.criarImediata(txid, BigDecimal.TEN, CRIADA, EXPIRA))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("txid");
        }

        @Test
        @DisplayName("expiraEm igual ou anterior a criadaEm é rejeitado")
        void rejeitaExpiracaoNaoPosterior() {
            assertThatThrownBy(() -> Cobranca.criarImediata(TXID, BigDecimal.TEN, CRIADA, CRIADA))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("expiraEm");
        }
    }

    @Nested
    @DisplayName("transições de estado")
    class Transicoes {

        @Test
        @DisplayName("ATIVA pode expirar e atualizadaEm acompanha")
        void ativaExpira() {
            Cobranca c = imediata();

            c.expirar(AGORA);

            assertThat(c.getStatus()).isEqualTo(StatusCobranca.EXPIRADA);
            assertThat(c.getAtualizadaEm()).isEqualTo(AGORA);
        }

        @Test
        @DisplayName("cobrança já PAGA não pode expirar")
        void pagaNaoExpira() {
            Cobranca c = imediata();
            c.marcarComoPaga(AGORA);

            assertThatThrownBy(() -> c.expirar(AGORA))
                    .isInstanceOf(TransicaoInvalidaException.class)
                    .hasMessageContaining("PAGA")
                    .hasMessageContaining("EXPIRADA");
        }

        @Test
        @DisplayName("ATIVA pode ser paga")
        void ativaPaga() {
            Cobranca c = imediata();

            c.marcarComoPaga(AGORA);

            assertThat(c.getStatus()).isEqualTo(StatusCobranca.PAGA);
        }

        @Test
        @DisplayName("EXPIRADA ainda pode ser paga (webhook atrasado, validade decidida pelo horário do pagamento)")
        void expiradaPodeSerPaga() {
            Cobranca c = imediata();
            c.expirar(AGORA);

            c.marcarComoPaga(AGORA.plusSeconds(1));

            assertThat(c.getStatus()).isEqualTo(StatusCobranca.PAGA);
        }

        @Test
        @DisplayName("cobrança já PAGA não pode ser paga de novo")
        void pagaNaoPagaDeNovo() {
            Cobranca c = imediata();
            c.marcarComoPaga(AGORA);

            assertThatThrownBy(() -> c.marcarComoPaga(AGORA))
                    .isInstanceOf(TransicaoInvalidaException.class);
        }
    }

    @Test
    @DisplayName("igualdade é pela chave de negócio (txid)")
    void igualdadePorTxid() {
        Cobranca a = imediata();
        Cobranca b = Cobranca.criarImediata(TXID, new BigDecimal("999.00"), CRIADA, EXPIRA);
        Cobranca outra = Cobranca.criarImediata("zzzzzzzzzzzzzzzzzzzzzzzzzz", BigDecimal.TEN, CRIADA, EXPIRA);

        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
        assertThat(a).isNotEqualTo(outra);
    }
}
