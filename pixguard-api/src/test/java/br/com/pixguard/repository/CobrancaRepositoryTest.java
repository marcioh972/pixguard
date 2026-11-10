package br.com.pixguard.repository;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.pixguard.domain.Cobranca;
import br.com.pixguard.domain.StatusCobranca;
import br.com.pixguard.domain.TipoCobranca;
import br.com.pixguard.support.IntegracaoBase;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@DisplayName("CobrancaRepository (mapeamento JPA x schema do Flyway)")
class CobrancaRepositoryTest extends IntegracaoBase {

    private static final String TXID = "a1b2c3d4e5f60718293a4b5c6d7e8f90";
    private static final Instant CRIADA = Instant.parse("2026-11-05T12:00:00Z");
    private static final Instant EXPIRA = CRIADA.plusSeconds(3600);

    @Autowired
    private CobrancaRepository repository;

    @Test
    @DisplayName("persiste cobrança imediata e recarrega com todos os campos")
    void persisteImediata() {
        repository.save(Cobranca.criarImediata(TXID, new BigDecimal("110.00"), CRIADA, EXPIRA));

        Cobranca lida = repository.findByTxid(TXID).orElseThrow();

        assertThat(lida.getId()).isNotNull();
        assertThat(lida.getTipo()).isEqualTo(TipoCobranca.IMEDIATA);
        assertThat(lida.getStatus()).isEqualTo(StatusCobranca.ATIVA);
        assertThat(lida.getValor()).isEqualTo(new BigDecimal("110.00"));
        assertThat(lida.getDataVencimento()).isNull();
        assertThat(lida.getCriadaEm()).isEqualTo(CRIADA);
        assertThat(lida.getExpiraEm()).isEqualTo(EXPIRA);
        assertThat(lida.getAtualizadaEm()).isEqualTo(CRIADA);
        assertThat(lida.getVersion()).isZero();
    }

    @Test
    @DisplayName("persiste cobrança com vencimento e recarrega a data")
    void persisteComVencimento() {
        LocalDate vencimento = LocalDate.of(2026, 12, 10);
        repository.save(Cobranca.criarComVencimento(TXID, new BigDecimal("50.00"), vencimento, CRIADA, EXPIRA));

        Cobranca lida = repository.findByTxid(TXID).orElseThrow();

        assertThat(lida.getTipo()).isEqualTo(TipoCobranca.COM_VENCIMENTO);
        assertThat(lida.getDataVencimento()).isEqualTo(vencimento);
    }

    @Test
    @DisplayName("transição de estado persiste o novo status e incrementa a version")
    void transicaoIncrementaVersion() {
        repository.save(Cobranca.criarImediata(TXID, new BigDecimal("110.00"), CRIADA, EXPIRA));
        Cobranca c = repository.findByTxid(TXID).orElseThrow();

        c.expirar(relogio.instant());
        repository.save(c);

        Cobranca recarregada = repository.findByTxid(TXID).orElseThrow();
        assertThat(recarregada.getStatus()).isEqualTo(StatusCobranca.EXPIRADA);
        assertThat(recarregada.getVersion()).isEqualTo(1);
    }
}
