package br.com.pixguard.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.pixguard.exception.TransicaoInvalidaException;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Pagamento (domínio, sem Spring)")
class PagamentoTest {

    private static final String E2E = "E00000000202611051200abcdef00001";
    private static final String TXID = "a1b2c3d4e5f60718293a4b5c6d7e8f90";
    private static final Instant CRIADA = Instant.parse("2026-11-05T12:00:00Z");

    private static Pagamento novo() {
        return Pagamento.aguardandoCobranca(E2E, TXID, new BigDecimal("110.00"), CRIADA, CRIADA);
    }

    private static Cobranca cobranca() {
        return Cobranca.criarImediata(TXID, new BigDecimal("110.00"), CRIADA, CRIADA.plusSeconds(3600));
    }

    @Test
    @DisplayName("nasce AGUARDANDO_COBRANCA, sem cobrança e com valor devolvido zero")
    void nasceAguardando() {
        Pagamento p = novo();

        assertThat(p.getStatus()).isEqualTo(StatusPagamento.AGUARDANDO_COBRANCA);
        assertThat(p.getCobranca()).isNull();
        assertThat(p.getValorDevolvido()).isEqualTo(new BigDecimal("0.00"));
    }

    @Test
    @DisplayName("endToEndId com tamanho diferente de 32 é rejeitado")
    void rejeitaEndToEndIdInvalido() {
        assertThatThrownBy(() -> Pagamento.aguardandoCobranca(
                E2E.substring(1), TXID, BigDecimal.TEN, CRIADA, CRIADA))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("endToEndId");
    }

    @Test
    @DisplayName("conciliar vincula a cobrança e vira CONCILIADO")
    void conciliar() {
        Pagamento p = novo();
        Cobranca c = cobranca();

        p.conciliar(c);

        assertThat(p.getStatus()).isEqualTo(StatusPagamento.CONCILIADO);
        assertThat(p.getCobranca()).isSameAs(c);
    }

    @Test
    @DisplayName("recebido após expiração vincula a cobrança e vira RECEBIDO_APOS_EXPIRACAO")
    void aposExpiracao() {
        Pagamento p = novo();

        p.marcarRecebidoAposExpiracao(cobranca());

        assertThat(p.getStatus()).isEqualTo(StatusPagamento.RECEBIDO_APOS_EXPIRACAO);
        assertThat(p.getCobranca()).isNotNull();
    }

    @Test
    @DisplayName("excedente vincula a cobrança e vira EXCEDENTE")
    void excedente() {
        Pagamento p = novo();

        p.marcarExcedente(cobranca());

        assertThat(p.getStatus()).isEqualTo(StatusPagamento.EXCEDENTE);
    }

    @Test
    @DisplayName("pagamento já CONCILIADO não pode mudar de estado de novo")
    void conciliadoNaoMuda() {
        Pagamento p = novo();
        p.conciliar(cobranca());

        assertThatThrownBy(() -> p.marcarExcedente(cobranca()))
                .isInstanceOf(TransicaoInvalidaException.class)
                .hasMessageContaining("CONCILIADO")
                .hasMessageContaining("EXCEDENTE");
        assertThatThrownBy(() -> p.conciliar(cobranca()))
                .isInstanceOf(TransicaoInvalidaException.class);
    }

    @Test
    @DisplayName("igualdade é pela chave de negócio (endToEndId)")
    void igualdadePorEndToEndId() {
        Pagamento a = novo();
        Pagamento b = Pagamento.aguardandoCobranca(E2E, TXID, new BigDecimal("999.00"), CRIADA, CRIADA);
        Pagamento outro = Pagamento.aguardandoCobranca(
                "E00000000202611051200abcdef00002", TXID, BigDecimal.TEN, CRIADA, CRIADA);

        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
        assertThat(a).isNotEqualTo(outro);
    }
}
