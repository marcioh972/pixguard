package br.com.pixguard.domain;

import br.com.pixguard.exception.TransicaoInvalidaException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "pagamento")
public class Pagamento {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "end_to_end_id", nullable = false, updatable = false, length = 32)
    private String endToEndId;

    @Column(name = "txid", nullable = false, updatable = false, length = 35)
    private String txid;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cobranca_id")
    private Cobranca cobranca;

    @Column(name = "valor", nullable = false, updatable = false, precision = 15, scale = 2)
    private BigDecimal valor;

    /**
     * Só leitura de propósito: o saldo é alterado por UPDATE atômico no banco (etapa 5), nunca por
     * "ler, somar e gravar" na entidade, que perderia devoluções concorrentes.
     */
    @Column(name = "valor_devolvido", nullable = false, insertable = false, updatable = false,
            precision = 15, scale = 2)
    private BigDecimal valorDevolvido;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private StatusPagamento status;

    @Column(name = "pago_em", nullable = false, updatable = false)
    private Instant pagoEm;

    @Column(name = "recebido_em", nullable = false, updatable = false)
    private Instant recebidoEm;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    /** Exigido pelo JPA. */
    protected Pagamento() {
    }

    private Pagamento(String endToEndId, String txid, BigDecimal valor, Instant pagoEm, Instant recebidoEm) {
        this.endToEndId = Validacoes.endToEndId(endToEndId);
        this.txid = Validacoes.txid(txid);
        this.valor = Validacoes.valor(valor);
        this.valorDevolvido = BigDecimal.ZERO.setScale(2);
        this.pagoEm = Validacoes.obrigatorio(pagoEm, "pagoEm");
        this.recebidoEm = Validacoes.obrigatorio(recebidoEm, "recebidoEm");
        this.status = StatusPagamento.AGUARDANDO_COBRANCA;
    }

    /**
     * Construção no lado do domínio (testes e fixtures). Em produção o webhook insere por
     * INSERT ... ON CONFLICT DO NOTHING (ver PagamentoRepository), que é o que garante a idempotência.
     */
    public static Pagamento aguardandoCobranca(String endToEndId, String txid, BigDecimal valor,
                                               Instant pagoEm, Instant recebidoEm) {
        return new Pagamento(endToEndId, txid, valor, pagoEm, recebidoEm);
    }

    /** AGUARDANDO_COBRANCA -> CONCILIADO. */
    public void conciliar(Cobranca cobranca) {
        mudarPara(StatusPagamento.CONCILIADO, cobranca);
    }

    /** AGUARDANDO_COBRANCA -> RECEBIDO_APOS_EXPIRACAO (dinheiro entrou, é elegível a devolução). */
    public void marcarRecebidoAposExpiracao(Cobranca cobranca) {
        mudarPara(StatusPagamento.RECEBIDO_APOS_EXPIRACAO, cobranca);
    }

    /** AGUARDANDO_COBRANCA -> EXCEDENTE (a cobrança já havia sido paga por outro endToEndId). */
    public void marcarExcedente(Cobranca cobranca) {
        mudarPara(StatusPagamento.EXCEDENTE, cobranca);
    }

    private void mudarPara(StatusPagamento novo, Cobranca cobranca) {
        if (status != StatusPagamento.AGUARDANDO_COBRANCA) {
            throw new TransicaoInvalidaException("Pagamento", endToEndId, status, novo);
        }
        this.cobranca = Validacoes.obrigatorio(cobranca, "cobranca");
        this.status = novo;
    }

    public Long getId() {
        return id;
    }

    public String getEndToEndId() {
        return endToEndId;
    }

    public String getTxid() {
        return txid;
    }

    public Cobranca getCobranca() {
        return cobranca;
    }

    public BigDecimal getValor() {
        return valor;
    }

    public BigDecimal getValorDevolvido() {
        return valorDevolvido;
    }

    public StatusPagamento getStatus() {
        return status;
    }

    public Instant getPagoEm() {
        return pagoEm;
    }

    public Instant getRecebidoEm() {
        return recebidoEm;
    }

    public long getVersion() {
        return version;
    }

    /** Igualdade pela chave de negócio (endToEndId). Usa getter por causa dos proxies do Hibernate. */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Pagamento outro)) {
            return false;
        }
        return endToEndId != null && endToEndId.equals(outro.getEndToEndId());
    }

    @Override
    public int hashCode() {
        return endToEndId == null ? 0 : endToEndId.hashCode();
    }
}
