package br.com.pixguard.domain;

import br.com.pixguard.exception.TransicaoInvalidaException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "cobranca")
public class Cobranca {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "txid", nullable = false, updatable = false, length = 35)
    private String txid;

    @Enumerated(EnumType.STRING)
    @Column(name = "tipo", nullable = false, updatable = false, length = 20)
    private TipoCobranca tipo;

    @Column(name = "valor", nullable = false, updatable = false, precision = 15, scale = 2)
    private BigDecimal valor;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private StatusCobranca status;

    @Column(name = "data_vencimento", updatable = false)
    private LocalDate dataVencimento;

    @Column(name = "criada_em", nullable = false, updatable = false)
    private Instant criadaEm;

    @Column(name = "expira_em", nullable = false, updatable = false)
    private Instant expiraEm;

    @Column(name = "atualizada_em", nullable = false)
    private Instant atualizadaEm;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    /** Exigido pelo JPA. */
    protected Cobranca() {
    }

    private Cobranca(String txid, TipoCobranca tipo, BigDecimal valor, LocalDate dataVencimento,
                     Instant criadaEm, Instant expiraEm) {
        Validacoes.obrigatorio(criadaEm, "criadaEm");
        Validacoes.obrigatorio(expiraEm, "expiraEm");
        if (!expiraEm.isAfter(criadaEm)) {
            throw new IllegalArgumentException("expiraEm deve ser posterior a criadaEm");
        }
        this.txid = Validacoes.txid(txid);
        this.tipo = tipo;
        this.valor = Validacoes.valor(valor);
        this.dataVencimento = dataVencimento;
        this.status = StatusCobranca.ATIVA;
        this.criadaEm = criadaEm;
        this.expiraEm = expiraEm;
        this.atualizadaEm = criadaEm;
    }

    public static Cobranca criarImediata(String txid, BigDecimal valor, Instant criadaEm, Instant expiraEm) {
        return new Cobranca(txid, TipoCobranca.IMEDIATA, valor, null, criadaEm, expiraEm);
    }

    public static Cobranca criarComVencimento(String txid, BigDecimal valor, LocalDate dataVencimento,
                                              Instant criadaEm, Instant expiraEm) {
        Validacoes.obrigatorio(dataVencimento, "dataVencimento");
        return new Cobranca(txid, TipoCobranca.COM_VENCIMENTO, valor, dataVencimento, criadaEm, expiraEm);
    }

    /** ATIVA -> EXPIRADA. */
    public void expirar(Instant agora) {
        if (status != StatusCobranca.ATIVA) {
            throw new TransicaoInvalidaException("Cobrança", txid, status, StatusCobranca.EXPIRADA);
        }
        status = StatusCobranca.EXPIRADA;
        atualizadaEm = Validacoes.obrigatorio(agora, "agora");
    }

    /**
     * ATIVA ou EXPIRADA -> PAGA. EXPIRADA é aceita porque o job pode expirar a cobrança antes de
     * um webhook atrasado chegar; quem decide se o pagamento ainda valia é o horário do pagamento
     * (regra aplicada na etapa 3, no serviço do webhook).
     */
    public void marcarComoPaga(Instant agora) {
        if (status != StatusCobranca.ATIVA && status != StatusCobranca.EXPIRADA) {
            throw new TransicaoInvalidaException("Cobrança", txid, status, StatusCobranca.PAGA);
        }
        status = StatusCobranca.PAGA;
        atualizadaEm = Validacoes.obrigatorio(agora, "agora");
    }

    /**
     * Reflete a devolução do pagamento que baixou esta cobrança: PAGA (nada devolvido),
     * DEVOLVIDA_PARCIAL ou DEVOLVIDA_TOTAL (tudo devolvido). Só vale para cobrança já paga
     * (ou já devolvida). O status é sempre derivado do saldo, então a mesma chamada serve para
     * uma nova devolução e para o estorno de uma devolução NAO_REALIZADO.
     */
    public void atualizarPorDevolucao(BigDecimal valorDevolvido, BigDecimal valorPago, Instant agora) {
        Validacoes.obrigatorio(valorDevolvido, "valorDevolvido");
        Validacoes.obrigatorio(valorPago, "valorPago");
        if (valorDevolvido.signum() < 0 || valorDevolvido.compareTo(valorPago) > 0) {
            throw new IllegalArgumentException("valorDevolvido deve estar entre 0 e o valor pago");
        }
        StatusCobranca novo;
        if (valorDevolvido.signum() == 0) {
            novo = StatusCobranca.PAGA;
        } else if (valorDevolvido.compareTo(valorPago) == 0) {
            novo = StatusCobranca.DEVOLVIDA_TOTAL;
        } else {
            novo = StatusCobranca.DEVOLVIDA_PARCIAL;
        }
        if (status != StatusCobranca.PAGA && status != StatusCobranca.DEVOLVIDA_PARCIAL
                && status != StatusCobranca.DEVOLVIDA_TOTAL) {
            throw new TransicaoInvalidaException("Cobrança", txid, status, novo);
        }
        if (novo != status) {
            status = novo;
            atualizadaEm = Validacoes.obrigatorio(agora, "agora");
        }
    }

    public Long getId() {
        return id;
    }

    public String getTxid() {
        return txid;
    }

    public TipoCobranca getTipo() {
        return tipo;
    }

    public BigDecimal getValor() {
        return valor;
    }

    public StatusCobranca getStatus() {
        return status;
    }

    public LocalDate getDataVencimento() {
        return dataVencimento;
    }

    public Instant getCriadaEm() {
        return criadaEm;
    }

    public Instant getExpiraEm() {
        return expiraEm;
    }

    public Instant getAtualizadaEm() {
        return atualizadaEm;
    }

    public long getVersion() {
        return version;
    }

    /** Igualdade pela chave de negócio (txid). Usa getter por causa dos proxies do Hibernate. */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Cobranca outra)) {
            return false;
        }
        return txid != null && txid.equals(outra.getTxid());
    }

    @Override
    public int hashCode() {
        return txid == null ? 0 : txid.hashCode();
    }
}
