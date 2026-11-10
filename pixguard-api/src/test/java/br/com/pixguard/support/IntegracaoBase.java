package br.com.pixguard.support;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Base dos testes de integração: PostgreSQL real (nunca H2), UM container para toda a suíte,
 * banco limpo e relógio reiniciado antes de cada teste.
 */
@SpringBootTest(properties = "pixguard.job.habilitado=false")
@AutoConfigureMockMvc
@Import({RelogioTesteConfig.class, TxidTesteConfig.class, BancoTesteConfig.class})
public abstract class IntegracaoBase {

    @ServiceConnection
    protected static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    static {
        // Iniciado uma única vez por JVM e encerrado pelo Ryuk ao final da suíte.
        POSTGRES.start();
    }

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected RelogioControlavel relogio;

    @Autowired
    protected GeradorTxidControlavel txids;

    @Autowired
    protected BancoClientFalso banco;

    @BeforeEach
    protected void limparBancoEReiniciarRelogio() {
        // Descobre as tabelas no catálogo: continua valendo quando as próximas etapas criarem novas.
        List<String> tabelas = jdbc.queryForList(
                "SELECT tablename FROM pg_tables WHERE schemaname = 'public' AND tablename <> 'flyway_schema_history'",
                String.class);
        if (!tabelas.isEmpty()) {
            jdbc.execute("TRUNCATE TABLE " + String.join(", ", tabelas) + " RESTART IDENTITY CASCADE");
        }
        relogio.definir(RelogioControlavel.INICIO_PADRAO);
        txids.limpar();
        banco.limpar();
    }
}
