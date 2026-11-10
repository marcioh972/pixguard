package br.com.pixguard.repository;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.pixguard.support.IntegracaoBase;
import java.io.IOException;
import java.util.List;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

@DisplayName("Migrações do Flyway")
class MigracaoTest extends IntegracaoBase {

    private static final String SCHEMA_TESTE = "migracao_teste";

    @Autowired
    private DataSource dataSource;

    private int contarArquivosDeMigracao() throws IOException {
        return new PathMatchingResourcePatternResolver().getResources("classpath:db/migration/V*__*.sql").length;
    }

    @Test
    @DisplayName("todas as migrações aplicam do zero, em ordem, num schema vazio")
    void migracoesAplicamDoZero() throws IOException {
        jdbc.execute("DROP SCHEMA IF EXISTS " + SCHEMA_TESTE + " CASCADE");
        try {
            Flyway flyway = Flyway.configure()
                    .dataSource(dataSource)
                    .schemas(SCHEMA_TESTE)
                    .locations("classpath:db/migration")
                    .load();

            MigrateResult resultado = flyway.migrate();

            assertThat(resultado.success).isTrue();
            assertThat(resultado.migrationsExecuted).isEqualTo(contarArquivosDeMigracao());
            List<String> versoes = jdbc.queryForList(
                    "SELECT version FROM " + SCHEMA_TESTE + ".flyway_schema_history "
                            + "WHERE version IS NOT NULL AND success ORDER BY installed_rank", String.class);
            assertThat(versoes).containsExactly("1", "2", "3");
            List<String> tabelas = jdbc.queryForList(
                    "SELECT table_name FROM information_schema.tables WHERE table_schema = ? AND table_name <> 'flyway_schema_history'",
                    String.class, SCHEMA_TESTE);
            assertThat(tabelas).containsExactlyInAnyOrder("cobranca", "pagamento", "devolucao");
            flyway.validate();
        } finally {
            jdbc.execute("DROP SCHEMA IF EXISTS " + SCHEMA_TESTE + " CASCADE");
        }
    }

    @Test
    @DisplayName("o histórico do banco confere com os arquivos: nenhuma migração aplicada foi editada")
    void historicoConfereComOsArquivos() {
        Flyway flyway = Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load();

        flyway.validate(); // falha se o checksum de uma migração já aplicada mudou

        assertThat(flyway.info().pending()).as("nada pendente").isEmpty();
        assertThat(flyway.info().applied()).hasSizeGreaterThanOrEqualTo(3);
    }
}
