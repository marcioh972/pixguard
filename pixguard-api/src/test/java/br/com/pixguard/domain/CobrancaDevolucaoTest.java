package br.com.pixguard.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.pixguard.exception.TransicaoInvalidaException;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Cobranca: reflexo das devoluções no status (domínio, sem Spring)")
class CobrancaDevolucaoTest {

    private static final String TXID = "a1b2c3d4e5f60718293a4b5c6d7e8f90";
    private static final Instant CRIADA = Instant.parse("2026-11-05T12:00:00Z");
    private static final Instant AGORA = CRIADA.plusSeconds(600);
    private static final BigDecimal PAGO = new BigDecimal("100.00");

    private static Cobranca paga() {
        Cobranca c = Cobranca.criarImediata(TXID, PAGO, CRIADA, CRIADA.plusSeconds(3600));
        c.marcarComoPaga(CRIADA.plusSeconds(60));
        return c;
    }

    @Test
    @DisplayName("devolução parcial vira DEVOLVIDA_PARCIAL")
    void parcial() {
        Cobranca c = paga();

        c.atualizarPorDevolucao(new BigDecimal("40.00"), PAGO, AGORA);

        assertThat(c.getStatus()).isEqualTo(StatusCobranca.DEVOLVIDA_PARCIAL);
        assertThat(c.getAtualizadaEm()).isEqualTo(AGORA);
    }

    @Test
    @DisplayName("tudo devolvido vira DEVOLVIDA_TOTAL")
    void total() {
        Cobranca c = paga();

        c.atualizarPorDevolucao(PAGO, PAGO, AGORA);

        assertThat(c.getStatus()).isEqualTo(StatusCobranca.DEVOLVIDA_TOTAL);
    }

    @Test
    @DisplayName("estorno que zera o saldo devolvido volta a cobrança para PAGA")
    void estornoVoltaParaPaga() {
        Cobranca c = paga();
        c.atualizarPorDevolucao(PAGO, PAGO, AGORA);

        c.atualizarPorDevolucao(new BigDecimal("0.00"), PAGO, AGORA.plusSeconds(1));

        assertThat(c.getStatus()).isEqualTo(StatusCobranca.PAGA);
    }

    @Test
    @DisplayName("estorno parcial de uma devolução total volta para DEVOLVIDA_PARCIAL")
    void estornoParcial() {
        Cobranca c = paga();
        c.atualizarPorDevolucao(PAGO, PAGO, AGORA);

        c.atualizarPorDevolucao(new BigDecimal("30.00"), PAGO, AGORA.plusSeconds(1));

        assertThat(c.getStatus()).isEqualTo(StatusCobranca.DEVOLVIDA_PARCIAL);
    }

    @Test
    @DisplayName("sem mudança de status, atualizadaEm não muda")
    void semMudancaNaoToca() {
        Cobranca c = paga();
        Instant antes = c.getAtualizadaEm();

        c.atualizarPorDevolucao(new BigDecimal("0.00"), PAGO, AGORA);

        assertThat(c.getStatus()).isEqualTo(StatusCobranca.PAGA);
        assertThat(c.getAtualizadaEm()).isEqualTo(antes);
    }

    @Test
    @DisplayName("cobrança que não foi paga não aceita devolução")
    void ativaNaoAceita() {
        Cobranca ativa = Cobranca.criarImediata(TXID, PAGO, CRIADA, CRIADA.plusSeconds(3600));

        assertThatThrownBy(() -> ativa.atualizarPorDevolucao(new BigDecimal("10.00"), PAGO, AGORA))
                .isInstanceOf(TransicaoInvalidaException.class)
                .hasMessageContaining("ATIVA");
    }

    @Test
    @DisplayName("valor devolvido fora de [0, valor pago] é rejeitado")
    void rejeitaForaDoIntervalo() {
        Cobranca c = paga();

        assertThatThrownBy(() -> c.atualizarPorDevolucao(new BigDecimal("100.01"), PAGO, AGORA))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> c.atualizarPorDevolucao(new BigDecimal("-0.01"), PAGO, AGORA))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
