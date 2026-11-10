package br.com.pixguard.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.pixguard.exception.TransicaoInvalidaException;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("Devolucao (domínio, sem Spring)")
class DevolucaoTest {

    private static final String E2E = "E00000000202611051200abcdef00001";
    private static final String TXID = "a1b2c3d4e5f60718293a4b5c6d7e8f90";
    private static final Instant AGORA = Instant.parse("2026-11-05T12:00:00Z");

    private static Pagamento pagamento() {
        return Pagamento.aguardandoCobranca(E2E, TXID, new BigDecimal("100.00"), AGORA, AGORA);
    }

    private static Devolucao devolucao() {
        return Devolucao.solicitar("dev001", pagamento(), new BigDecimal("60.00"), "cliente desistiu", AGORA);
    }

    @Test
    @DisplayName("nasce EM_PROCESSAMENTO com os dados informados")
    void nasceEmProcessamento() {
        Devolucao d = devolucao();

        assertThat(d.getStatus()).isEqualTo(StatusDevolucao.EM_PROCESSAMENTO);
        assertThat(d.getValor()).isEqualTo(new BigDecimal("60.00"));
        assertThat(d.getMotivo()).isEqualTo("cliente desistiu");
        assertThat(d.getSolicitadaEm()).isEqualTo(AGORA);
    }

    @Test
    @DisplayName("motivo é opcional")
    void motivoOpcional() {
        Devolucao d = Devolucao.solicitar("dev001", pagamento(), BigDecimal.TEN, null, AGORA);

        assertThat(d.getMotivo()).isNull();
    }

    @ParameterizedTest(name = "idDevolucao \"{0}\" é rejeitado")
    @ValueSource(strings = {"", "com-hifen", "com espaço", "a123456789b123456789c123456789d12345"})
    @DisplayName("idDevolucao fora de 1 a 35 caracteres alfanuméricos é rejeitado")
    void rejeitaIdInvalido(String id) {
        assertThatThrownBy(() -> Devolucao.solicitar(id, pagamento(), BigDecimal.TEN, null, AGORA))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("idDevolucao");
    }

    @Test
    @DisplayName("motivo com mais de 140 caracteres é rejeitado")
    void rejeitaMotivoLongo() {
        assertThatThrownBy(() -> Devolucao.solicitar("dev001", pagamento(), BigDecimal.TEN, "x".repeat(141), AGORA))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("motivo");
    }

    @Test
    @DisplayName("valor com mais de 2 casas ou não positivo é rejeitado")
    void rejeitaValorInvalido() {
        assertThatThrownBy(() -> Devolucao.solicitar("dev001", pagamento(), new BigDecimal("1.001"), null, AGORA))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Devolucao.solicitar("dev001", pagamento(), BigDecimal.ZERO, null, AGORA))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("EM_PROCESSAMENTO pode ser concluída")
    void concluir() {
        Devolucao d = devolucao();

        d.concluir();

        assertThat(d.getStatus()).isEqualTo(StatusDevolucao.DEVOLVIDO);
    }

    @Test
    @DisplayName("EM_PROCESSAMENTO pode virar NAO_REALIZADO")
    void naoRealizada() {
        Devolucao d = devolucao();

        d.marcarNaoRealizada();

        assertThat(d.getStatus()).isEqualTo(StatusDevolucao.NAO_REALIZADO);
    }

    @Test
    @DisplayName("devolução já finalizada não muda de estado")
    void finalizadaNaoMuda() {
        Devolucao concluida = devolucao();
        concluida.concluir();
        Devolucao naoRealizada = devolucao();
        naoRealizada.marcarNaoRealizada();

        assertThatThrownBy(concluida::marcarNaoRealizada).isInstanceOf(TransicaoInvalidaException.class);
        assertThatThrownBy(concluida::concluir).isInstanceOf(TransicaoInvalidaException.class);
        assertThatThrownBy(naoRealizada::concluir)
                .isInstanceOf(TransicaoInvalidaException.class)
                .hasMessageContaining("NAO_REALIZADO")
                .hasMessageContaining("DEVOLVIDO");
    }
}
