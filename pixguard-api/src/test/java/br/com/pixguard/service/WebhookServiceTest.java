package br.com.pixguard.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import br.com.pixguard.config.WebhookProperties;
import br.com.pixguard.domain.Cobranca;
import br.com.pixguard.exception.FalhaTransitoriaException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.annotation.Transactional;

/**
 * O retry isolado do banco: o PagamentoService é dublê (é o colaborador, não o código testado).
 * O comportamento ponta a ponta, contra o PostgreSQL, está em WebhookConcorrenciaTest e WebhookRetentativaTest.
 */
@DisplayName("WebhookService: retry de conflitos de concorrência (sem Spring)")
class WebhookServiceTest {

    private static final int MAX_RETENTATIVAS = 3;

    private static final PixRecebido ITEM = new PixRecebido(
            "E00000000202611051200abcdef00001", "a1b2c3d4e5f60718293a4b5c6d7e8f90",
            new BigDecimal("110.00"), Instant.parse("2026-11-05T12:05:00Z"));
    private static final PixRecebido OUTRO_ITEM = new PixRecebido(
            "E00000000202611051200abcdef00002", "b2c3d4e5f60718293a4b5c6d7e8f9012a",
            new BigDecimal("50.00"), Instant.parse("2026-11-05T12:06:00Z"));

    private PagamentoService pagamentoService;
    private WebhookService webhookService;

    @BeforeEach
    void preparar() {
        pagamentoService = mock(PagamentoService.class);
        webhookService = new WebhookService(pagamentoService, new WebhookProperties(MAX_RETENTATIVAS, Duration.ZERO));
    }

    private static RuntimeException conflitoDeVersion() {
        return new ObjectOptimisticLockingFailureException(Cobranca.class, 1L);
    }

    @Test
    @DisplayName("conflito de @Version na primeira tentativa: repete e a segunda dá certo")
    void repeteEConsegue() {
        when(pagamentoService.processar(ITEM))
                .thenThrow(conflitoDeVersion())
                .thenReturn(ResultadoProcessamento.CONCILIADO);

        webhookService.processar(List.of(ITEM));

        verify(pagamentoService, times(2)).processar(ITEM);
    }

    @Test
    @DisplayName("repete até 3 vezes além da primeira tentativa; depois desiste com falha transitória (5xx)")
    void desisteDepoisDeEsgotar() {
        when(pagamentoService.processar(ITEM)).thenThrow(conflitoDeVersion());

        assertThatThrownBy(() -> webhookService.processar(List.of(ITEM)))
                .isInstanceOf(FalhaTransitoriaException.class);

        verify(pagamentoService, times(1 + MAX_RETENTATIVAS)).processar(ITEM);
    }

    @Test
    @DisplayName("com maxRetentativas = 0 não há retry")
    void semRetry() {
        WebhookService semRetry = new WebhookService(pagamentoService, new WebhookProperties(0, Duration.ZERO));
        when(pagamentoService.processar(ITEM)).thenThrow(conflitoDeVersion());

        assertThatThrownBy(() -> semRetry.processar(List.of(ITEM))).isInstanceOf(FalhaTransitoriaException.class);

        verify(pagamentoService, times(1)).processar(ITEM);
    }

    @Test
    @DisplayName("violação do índice de baixa única é repetida")
    void repeteViolacaoDeBaixaUnica() {
        DataIntegrityViolationException baixaDupla = new DataIntegrityViolationException("violação",
                new ConstraintViolationException("violação", new SQLException("x"), "uk_pagamento_cobranca_conciliado"));
        when(pagamentoService.processar(ITEM))
                .thenThrow(baixaDupla)
                .thenReturn(ResultadoProcessamento.EXCEDENTE);

        webhookService.processar(List.of(ITEM));

        verify(pagamentoService, times(2)).processar(ITEM);
    }

    @Test
    @DisplayName("erro que não é de concorrência NÃO é repetido (nada de mascarar bug com retry)")
    void naoRepeteErroComum() {
        when(pagamentoService.processar(ITEM)).thenThrow(new IllegalStateException("bug"));

        assertThatThrownBy(() -> webhookService.processar(List.of(ITEM))).isInstanceOf(FalhaTransitoriaException.class);

        verify(pagamentoService, times(1)).processar(ITEM);
    }

    @Test
    @DisplayName("outra violação de constraint NÃO é repetida")
    void naoRepeteOutraConstraint() {
        DataIntegrityViolationException invalido = new DataIntegrityViolationException("violação",
                new ConstraintViolationException("violação", new SQLException("x"), "ck_pagamento_valor_positivo"));
        when(pagamentoService.processar(ITEM)).thenThrow(invalido);

        assertThatThrownBy(() -> webhookService.processar(List.of(ITEM))).isInstanceOf(FalhaTransitoriaException.class);

        verify(pagamentoService, times(1)).processar(ITEM);
    }

    @Test
    @DisplayName("itens independentes: um que esgota as tentativas não impede o processamento do seguinte")
    void umItemFalhandoNaoImpedeOSeguinte() {
        when(pagamentoService.processar(ITEM)).thenThrow(conflitoDeVersion());
        when(pagamentoService.processar(OUTRO_ITEM)).thenReturn(ResultadoProcessamento.CONCILIADO);

        assertThatThrownBy(() -> webhookService.processar(List.of(ITEM, OUTRO_ITEM)))
                .isInstanceOf(FalhaTransitoriaException.class)
                .hasMessageContaining("1 item");

        verify(pagamentoService, times(1 + MAX_RETENTATIVAS)).processar(ITEM);
        verify(pagamentoService, times(1)).processar(OUTRO_ITEM);
    }

    @Test
    @DisplayName("sem conflito, cada item é processado uma única vez")
    void semConflito() {
        when(pagamentoService.processar(ITEM)).thenReturn(ResultadoProcessamento.CONCILIADO);
        when(pagamentoService.processar(OUTRO_ITEM)).thenReturn(ResultadoProcessamento.AGUARDANDO_COBRANCA);

        webhookService.processar(List.of(ITEM, OUTRO_ITEM));

        verify(pagamentoService, times(1)).processar(ITEM);
        verify(pagamentoService, times(1)).processar(OUTRO_ITEM);
    }

    @Test
    @DisplayName("a fachada NÃO é transacional: o retry precisa acontecer FORA da transação")
    void fachadaNaoETransacional() {
        assertThat(AnnotationUtils.findAnnotation(WebhookService.class, Transactional.class)).isNull();
        for (Method metodo : WebhookService.class.getDeclaredMethods()) {
            assertThat(AnnotationUtils.findAnnotation(metodo, Transactional.class))
                    .as("método %s", metodo.getName()).isNull();
        }
    }
}
