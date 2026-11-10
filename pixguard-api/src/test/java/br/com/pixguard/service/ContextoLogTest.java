package br.com.pixguard.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

@DisplayName("ContextoLog (MDC limpo a cada item)")
class ContextoLogTest {

    @AfterEach
    void limparMdc() {
        MDC.clear();
    }

    @Test
    @DisplayName("coloca txid e endToEndId no MDC e remove ao sair")
    void colocaERemove() {
        try (ContextoLog contexto = ContextoLog.de("txid-1", "e2e-1")) {
            assertThat(MDC.get("txid")).isEqualTo("txid-1");
            assertThat(MDC.get("endToEndId")).isEqualTo("e2e-1");
        }

        assertThat(MDC.get("txid")).isNull();
        assertThat(MDC.get("endToEndId")).isNull();
    }

    @Test
    @DisplayName("remove o contexto mesmo quando o item lança exceção")
    void removeNaExcecao() {
        assertThatThrownBy(() -> {
            try (ContextoLog contexto = ContextoLog.de("txid-1", "e2e-1")) {
                throw new IllegalStateException("falha no item");
            }
        }).isInstanceOf(IllegalStateException.class);

        assertThat(MDC.get("txid")).isNull();
        assertThat(MDC.get("endToEndId")).isNull();
    }

    @Test
    @DisplayName("só txid: não cria endToEndId")
    void soTxid() {
        try (ContextoLog contexto = ContextoLog.de("txid-1")) {
            assertThat(MDC.get("txid")).isEqualTo("txid-1");
            assertThat(MDC.get("endToEndId")).isNull();
        }

        assertThat(MDC.get("txid")).isNull();
    }

    @Test
    @DisplayName("contextos aninhados: o interno restaura o valor do externo ao fechar")
    void aninhadosRestauram() {
        try (ContextoLog externo = ContextoLog.de("txid-1")) {
            try (ContextoLog interno = ContextoLog.de("txid-1", "e2e-1")) {
                assertThat(MDC.get("endToEndId")).isEqualTo("e2e-1");
            }

            assertThat(MDC.get("txid")).isEqualTo("txid-1");
            assertThat(MDC.get("endToEndId")).isNull();
        }

        assertThat(MDC.get("txid")).isNull();
    }
}
