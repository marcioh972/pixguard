package br.com.pixguard.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.pixguard.support.IntegracaoBase;
import java.util.stream.Stream;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Prova que o BANCO rejeita dados inválidos por conta própria. Os INSERTs são SQL puro de propósito:
 * passar pelo domínio Java esconderia uma constraint ausente no schema. Cada teste confere o NOME da
 * constraint, para garantir que falhou pela regra certa e não por outro motivo.
 */
@DisplayName("Constraints da tabela cobranca (V1)")
class CobrancaConstraintsTest extends IntegracaoBase {

    private static final String TXID_VALIDO = "a1b2c3d4e5f60718293a4b5c6d7e8f90";

    private void inserir(String txid, String tipo, String valor, String status,
                         String dataVencimento, String deslocamentoExpiracao) {
        jdbc.update("""
                INSERT INTO cobranca (txid, tipo, valor, status, data_vencimento, criada_em, expira_em, atualizada_em)
                VALUES (?, ?, ?::numeric, ?, ?::date, now(), now() + ?::interval, now())
                """, txid, tipo, valor, status, dataVencimento, deslocamentoExpiracao);
    }

    private void inserirImediataValida(String txid) {
        inserir(txid, "IMEDIATA", "10.00", "ATIVA", null, "1 hour");
    }

    private void assertRejeitadoPor(String constraint, ThrowingCallable insercao) {
        assertThatThrownBy(insercao)
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining(constraint);
    }

    private long total() {
        Long total = jdbc.queryForObject("SELECT count(*) FROM cobranca", Long.class);
        return total == null ? 0 : total;
    }

    // ---------- txid ----------

    @ParameterizedTest(name = "txid com {0} caracteres é aceito")
    @ValueSource(ints = {26, 32, 35})
    @DisplayName("txid alfanumérico entre 26 e 35 caracteres é aceito")
    void txidNosLimitesValidos(int tamanho) {
        inserirImediataValida("a".repeat(tamanho));

        assertThat(total()).isEqualTo(1);
    }

    static Stream<Arguments> txidsInvalidos() {
        return Stream.of(
                Arguments.of("curto demais (25)", "a".repeat(25)),
                // 31 caracteres (cabe no varchar(35)) mas com hífens: quem rejeita é o CHECK de formato, não o tamanho da coluna
                Arguments.of("com hífens", "123e4567-e89b-12d3-a456-4266141"),
                Arguments.of("com espaço", "a".repeat(25) + " "),
                Arguments.of("com símbolo", "a".repeat(25) + "_"));
    }

    @ParameterizedTest(name = "txid {0} é rejeitado")
    @MethodSource("txidsInvalidos")
    @DisplayName("txid fora do formato é rejeitado pelo banco")
    void txidForaDoFormato(String descricao, String txid) {
        assertRejeitadoPor("ck_cobranca_txid_formato", () -> inserirImediataValida(txid));
        assertThat(total()).isZero();
    }

    @Test
    @DisplayName("txid duplicado é rejeitado pelo banco")
    void txidDuplicado() {
        inserirImediataValida(TXID_VALIDO);

        assertRejeitadoPor("uk_cobranca_txid", () -> inserirImediataValida(TXID_VALIDO));
        assertThat(total()).isEqualTo(1);
    }

    // ---------- demais regras do V1 ----------

    @ParameterizedTest(name = "valor {0} é rejeitado")
    @ValueSource(strings = {"0.00", "-1.00"})
    @DisplayName("valor zero ou negativo é rejeitado")
    void valorNaoPositivo(String valor) {
        assertRejeitadoPor("ck_cobranca_valor_positivo",
                () -> inserir(TXID_VALIDO, "IMEDIATA", valor, "ATIVA", null, "1 hour"));
    }

    @Test
    @DisplayName("cobrança COM_VENCIMENTO sem data de vencimento é rejeitada")
    void comVencimentoSemData() {
        assertRejeitadoPor("ck_cobranca_vencimento",
                () -> inserir(TXID_VALIDO, "COM_VENCIMENTO", "10.00", "ATIVA", null, "1 hour"));
    }

    @Test
    @DisplayName("cobrança IMEDIATA com data de vencimento é rejeitada")
    void imediataComData() {
        assertRejeitadoPor("ck_cobranca_vencimento",
                () -> inserir(TXID_VALIDO, "IMEDIATA", "10.00", "ATIVA", "2026-12-10", "1 hour"));
    }

    @Test
    @DisplayName("cobrança COM_VENCIMENTO com data de vencimento é aceita")
    void comVencimentoComData() {
        inserir(TXID_VALIDO, "COM_VENCIMENTO", "10.00", "ATIVA", "2026-12-10", "1 hour");

        assertThat(total()).isEqualTo(1);
    }

    @Test
    @DisplayName("status desconhecido é rejeitado")
    void statusInvalido() {
        assertRejeitadoPor("ck_cobranca_status",
                () -> inserir(TXID_VALIDO, "IMEDIATA", "10.00", "CANCELADA", null, "1 hour"));
    }

    @Test
    @DisplayName("tipo desconhecido é rejeitado")
    void tipoInvalido() {
        assertRejeitadoPor("ck_cobranca_tipo",
                () -> inserir(TXID_VALIDO, "RECORRENTE", "10.00", "ATIVA", null, "1 hour"));
    }

    @ParameterizedTest(name = "expira_em = criada_em + {0} é rejeitado")
    @ValueSource(strings = {"0 seconds", "-1 hour"})
    @DisplayName("expira_em igual ou anterior a criada_em é rejeitado")
    void expiracaoNaoPosterior(String deslocamento) {
        assertRejeitadoPor("ck_cobranca_expira_apos_criacao",
                () -> inserir(TXID_VALIDO, "IMEDIATA", "10.00", "ATIVA", null, deslocamento));
    }
}
