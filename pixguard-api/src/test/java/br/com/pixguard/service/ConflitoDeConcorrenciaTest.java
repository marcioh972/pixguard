package br.com.pixguard.service;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.pixguard.domain.Cobranca;
import java.sql.SQLException;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

@DisplayName("ConflitoDeConcorrencia: o que vale repetir")
class ConflitoDeConcorrenciaTest {

    private static DataIntegrityViolationException violacaoDe(String constraint) {
        return new DataIntegrityViolationException("violação",
                new ConstraintViolationException("violação", new SQLException("x"), constraint));
    }

    @Test
    @DisplayName("conflito de @Version é retentável")
    void conflitoDeVersion() {
        assertThat(ConflitoDeConcorrencia.ehRetentavel(
                new ObjectOptimisticLockingFailureException(Cobranca.class, 1L))).isTrue();
    }

    @Test
    @DisplayName("deadlock (falha de lock) é retentável")
    void deadlock() {
        assertThat(ConflitoDeConcorrencia.ehRetentavel(new CannotAcquireLockException("deadlock detected"))).isTrue();
    }

    @Test
    @DisplayName("conflito embrulhado em outra exceção continua sendo reconhecido")
    void embrulhado() {
        RuntimeException embrulho = new RuntimeException("falha ao commitar",
                new ObjectOptimisticLockingFailureException(Cobranca.class, 1L));

        assertThat(ConflitoDeConcorrencia.ehRetentavel(embrulho)).isTrue();
    }

    @Test
    @DisplayName("violação do índice de baixa única (duas baixas disputando a mesma cobrança) é retentável")
    void baixaUnica() {
        assertThat(ConflitoDeConcorrencia.ehRetentavel(violacaoDe("uk_pagamento_cobranca_conciliado"))).isTrue();
    }

    @Test
    @DisplayName("a violação é reconhecida também só pela mensagem do driver")
    void baixaUnicaPelaMensagem() {
        DataIntegrityViolationException erro = new DataIntegrityViolationException(
                "ERROR: duplicate key value violates unique constraint \"uk_pagamento_cobranca_conciliado\"");

        assertThat(ConflitoDeConcorrencia.ehRetentavel(erro)).isTrue();
    }

    @Test
    @DisplayName("outras violações de constraint NÃO são retentáveis (seriam dado inválido ou bug)")
    void outrasViolacoesNaoSao() {
        assertThat(ConflitoDeConcorrencia.ehRetentavel(violacaoDe("ck_pagamento_valor_positivo"))).isFalse();
        assertThat(ConflitoDeConcorrencia.ehRetentavel(violacaoDe("uk_pagamento_end_to_end_id"))).isFalse();
        assertThat(ConflitoDeConcorrencia.ehRetentavel(
                new DataIntegrityViolationException("ERROR: violates check constraint \"ck_x\""))).isFalse();
    }

    @Test
    @DisplayName("erros comuns não são retentáveis")
    void errosComunsNaoSao() {
        assertThat(ConflitoDeConcorrencia.ehRetentavel(new IllegalStateException("bug"))).isFalse();
        assertThat(ConflitoDeConcorrencia.ehRetentavel(new IllegalArgumentException("dado inválido"))).isFalse();
        assertThat(ConflitoDeConcorrencia.ehRetentavel(new RuntimeException("x", new IllegalStateException()))).isFalse();
    }
}
