package br.com.pixguard.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.pixguard.support.IntegracaoBase;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Prova que o BANCO impõe as regras da tabela pagamento (V2), com SQL puro e conferindo o nome
 * da constraint violada. É aqui que a regra central ("nunca baixar duas vezes") é testada na raiz.
 */
@DisplayName("Constraints da tabela pagamento (V2)")
class PagamentoConstraintsTest extends IntegracaoBase {

    private static final String TXID = "a1b2c3d4e5f60718293a4b5c6d7e8f90";
    private static final String E2E_1 = "E00000000202611051200abcdef00001";
    private static final String E2E_2 = "E00000000202611051200abcdef00002";

    private long inserirCobranca() {
        Long id = jdbc.queryForObject("""
                INSERT INTO cobranca (txid, tipo, valor, status, criada_em, expira_em, atualizada_em)
                VALUES (?, 'IMEDIATA', 100.00, 'ATIVA', now(), now() + interval '1 hour', now())
                RETURNING id
                """, Long.class, TXID);
        return id == null ? 0 : id;
    }

    private void inserir(String e2e, String txid, String valor, String valorDevolvido,
                         String status, Long cobrancaId) {
        jdbc.update("""
                INSERT INTO pagamento (end_to_end_id, txid, cobranca_id, valor, valor_devolvido, status,
                                       pago_em, recebido_em)
                VALUES (?, ?, ?, ?::numeric, ?::numeric, ?, now(), now())
                """, e2e, txid, cobrancaId, valor, valorDevolvido, status);
    }

    private void assertRejeitadoPor(String constraint, ThrowingCallable insercao) {
        assertThatThrownBy(insercao)
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining(constraint);
    }

    private long total() {
        Long total = jdbc.queryForObject("SELECT count(*) FROM pagamento", Long.class);
        return total == null ? 0 : total;
    }

    @Test
    @DisplayName("pagamento aguardando cobrança com txid inexistente é aceito (sem FK de propósito)")
    void semFkNoTxid() {
        inserir(E2E_1, TXID, "100.00", "0", "AGUARDANDO_COBRANCA", null);

        assertThat(total()).isEqualTo(1);
    }

    @Test
    @DisplayName("endToEndId duplicado é rejeitado")
    void endToEndIdDuplicado() {
        inserir(E2E_1, TXID, "100.00", "0", "AGUARDANDO_COBRANCA", null);

        assertRejeitadoPor("uk_pagamento_end_to_end_id",
                () -> inserir(E2E_1, TXID, "100.00", "0", "AGUARDANDO_COBRANCA", null));
        assertThat(total()).isEqualTo(1);
    }

    @Test
    @DisplayName("endToEndId com tamanho diferente de 32 é rejeitado")
    void endToEndIdTamanho() {
        assertRejeitadoPor("ck_pagamento_end_to_end_id_tamanho",
                () -> inserir(E2E_1.substring(1), TXID, "100.00", "0", "AGUARDANDO_COBRANCA", null));
    }

    @Test
    @DisplayName("txid fora do formato é rejeitado")
    void txidFormato() {
        assertRejeitadoPor("ck_pagamento_txid_formato",
                () -> inserir(E2E_1, "curto", "100.00", "0", "AGUARDANDO_COBRANCA", null));
    }

    @Test
    @DisplayName("valor zero é rejeitado")
    void valorZero() {
        assertRejeitadoPor("ck_pagamento_valor_positivo",
                () -> inserir(E2E_1, TXID, "0.00", "0", "AGUARDANDO_COBRANCA", null));
    }

    @Test
    @DisplayName("valor negativo é rejeitado (o PostgreSQL aponta ck_pagamento_valor_devolvido, que falha antes: 0 <= -1.00 é falso)")
    void valorNegativo() {
        assertThatThrownBy(() -> inserir(E2E_1, TXID, "-1.00", "0", "AGUARDANDO_COBRANCA", null))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(total()).isZero();
    }

    @Test
    @DisplayName("valor_devolvido maior que o valor é rejeitado")
    void devolvidoMaiorQueValor() {
        assertRejeitadoPor("ck_pagamento_valor_devolvido",
                () -> inserir(E2E_1, TXID, "100.00", "100.01", "AGUARDANDO_COBRANCA", null));
    }

    @Test
    @DisplayName("valor_devolvido igual ao valor é aceito")
    void devolvidoIgualAoValor() {
        long cobrancaId = inserirCobranca();

        inserir(E2E_1, TXID, "100.00", "100.00", "CONCILIADO", cobrancaId);

        assertThat(total()).isEqualTo(1);
    }

    @Test
    @DisplayName("valor_devolvido negativo é rejeitado")
    void devolvidoNegativo() {
        assertRejeitadoPor("ck_pagamento_valor_devolvido",
                () -> inserir(E2E_1, TXID, "100.00", "-0.01", "AGUARDANDO_COBRANCA", null));
    }

    @Test
    @DisplayName("status desconhecido é rejeitado")
    void statusInvalido() {
        assertRejeitadoPor("ck_pagamento_status",
                () -> inserir(E2E_1, TXID, "100.00", "0", "PROCESSADO", null));
    }

    @Test
    @DisplayName("AGUARDANDO_COBRANCA com cobrança vinculada é rejeitado")
    void aguardandoComCobranca() {
        long cobrancaId = inserirCobranca();

        assertRejeitadoPor("ck_pagamento_status_cobranca",
                () -> inserir(E2E_1, TXID, "100.00", "0", "AGUARDANDO_COBRANCA", cobrancaId));
    }

    @ParameterizedTest(name = "{0} sem cobrança vinculada é rejeitado")
    @ValueSource(strings = {"CONCILIADO", "RECEBIDO_APOS_EXPIRACAO", "EXCEDENTE"})
    @DisplayName("pagamento já resolvido sem cobrança vinculada é rejeitado")
    void resolvidoSemCobranca(String status) {
        assertRejeitadoPor("ck_pagamento_status_cobranca",
                () -> inserir(E2E_1, TXID, "100.00", "0", status, null));
    }

    @Test
    @DisplayName("cobrança inexistente na FK é rejeitada")
    void fkCobranca() {
        assertRejeitadoPor("fk_pagamento_cobranca",
                () -> inserir(E2E_1, TXID, "100.00", "0", "CONCILIADO", 999_999L));
    }

    @Test
    @DisplayName("dois pagamentos CONCILIADOS para a mesma cobrança são rejeitados (baixa dupla)")
    void baixaDupla() {
        long cobrancaId = inserirCobranca();
        inserir(E2E_1, TXID, "100.00", "0", "CONCILIADO", cobrancaId);

        assertRejeitadoPor("uk_pagamento_cobranca_conciliado",
                () -> inserir(E2E_2, TXID, "100.00", "0", "CONCILIADO", cobrancaId));
        assertThat(total()).isEqualTo(1);
    }

    @Test
    @DisplayName("um CONCILIADO e vários EXCEDENTE/RECEBIDO_APOS_EXPIRACAO na mesma cobrança são aceitos")
    void umConciliadoEOutrosDesfechos() {
        long cobrancaId = inserirCobranca();

        inserir(E2E_1, TXID, "100.00", "0", "CONCILIADO", cobrancaId);
        inserir(E2E_2, TXID, "100.00", "0", "EXCEDENTE", cobrancaId);
        inserir("E00000000202611051200abcdef00003", TXID, "100.00", "0", "RECEBIDO_APOS_EXPIRACAO", cobrancaId);

        assertThat(total()).isEqualTo(3);
    }
}
