package br.com.pixguard.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.pixguard.support.IntegracaoBase;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataIntegrityViolationException;

/** O BANCO impõe as regras da tabela devolucao (V3). SQL puro, conferindo o nome da constraint violada. */
@DisplayName("Constraints da tabela devolucao (V3)")
class DevolucaoConstraintsTest extends IntegracaoBase {

    private static final String TXID = "a1b2c3d4e5f60718293a4b5c6d7e8f90";
    private static final String E2E = "E00000000202611051200abcdef00001";

    private long pagamentoId;

    @BeforeEach
    void criarPagamento() {
        Long cobrancaId = jdbc.queryForObject("""
                INSERT INTO cobranca (txid, tipo, valor, status, criada_em, expira_em, atualizada_em)
                VALUES (?, 'IMEDIATA', 100.00, 'PAGA', now(), now() + interval '1 hour', now())
                RETURNING id
                """, Long.class, TXID);
        Long id = jdbc.queryForObject("""
                INSERT INTO pagamento (end_to_end_id, txid, cobranca_id, valor, status, pago_em, recebido_em)
                VALUES (?, ?, ?, 100.00, 'CONCILIADO', now(), now())
                RETURNING id
                """, Long.class, E2E, TXID, cobrancaId);
        pagamentoId = id == null ? 0 : id;
    }

    private void inserir(String idDevolucao, long pagamento, String valor, String status, String motivo) {
        jdbc.update("""
                INSERT INTO devolucao (id_devolucao, pagamento_id, valor, status, motivo, solicitada_em)
                VALUES (?, ?, ?::numeric, ?, ?, now())
                """, idDevolucao, pagamento, valor, status, motivo);
    }

    private void inserirValida(String idDevolucao) {
        inserir(idDevolucao, pagamentoId, "60.00", "EM_PROCESSAMENTO", null);
    }

    private void assertRejeitadoPor(String constraint, ThrowingCallable insercao) {
        assertThatThrownBy(insercao)
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining(constraint);
    }

    private long total() {
        Long total = jdbc.queryForObject("SELECT count(*) FROM devolucao", Long.class);
        return total == null ? 0 : total;
    }

    @Test
    @DisplayName("devolução válida, com e sem motivo, é aceita")
    void valida() {
        inserirValida("dev001");
        inserir("dev002", pagamentoId, "10.00", "DEVOLVIDO", "produto com defeito");

        assertThat(total()).isEqualTo(2);
    }

    @Test
    @DisplayName("idDevolucao duplicado é rejeitado")
    void idDuplicado() {
        inserirValida("dev001");

        assertRejeitadoPor("uk_devolucao_id_devolucao", () -> inserirValida("dev001"));
        assertThat(total()).isEqualTo(1);
    }

    @ParameterizedTest(name = "idDevolucao \"{0}\" é rejeitado")
    @ValueSource(strings = {"", "com-hifen", "com espaço", "com_underscore"})
    @DisplayName("idDevolucao fora do formato é rejeitado")
    void idForaDoFormato(String id) {
        assertRejeitadoPor("ck_devolucao_id_formato", () -> inserirValida(id));
    }

    @Test
    @DisplayName("pagamento inexistente na FK é rejeitado")
    void fkPagamento() {
        assertRejeitadoPor("fk_devolucao_pagamento",
                () -> inserir("dev001", 999_999L, "60.00", "EM_PROCESSAMENTO", null));
    }

    @ParameterizedTest(name = "valor {0} é rejeitado")
    @ValueSource(strings = {"0.00", "-1.00"})
    @DisplayName("valor zero ou negativo é rejeitado")
    void valorNaoPositivo(String valor) {
        assertRejeitadoPor("ck_devolucao_valor_positivo",
                () -> inserir("dev001", pagamentoId, valor, "EM_PROCESSAMENTO", null));
    }

    @Test
    @DisplayName("status desconhecido é rejeitado")
    void statusInvalido() {
        assertRejeitadoPor("ck_devolucao_status",
                () -> inserir("dev001", pagamentoId, "60.00", "CONCLUIDA", null));
    }

    @Test
    @DisplayName("motivo com mais de 140 caracteres é rejeitado")
    void motivoLongo() {
        assertThatThrownBy(() -> inserir("dev001", pagamentoId, "60.00", "EM_PROCESSAMENTO", "x".repeat(141)))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(total()).isZero();
    }
}
