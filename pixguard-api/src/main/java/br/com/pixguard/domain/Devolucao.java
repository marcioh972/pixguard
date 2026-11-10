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
@Table(name = "devolucao")
public class Devolucao {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "id_devolucao", nullable = false, updatable = false, length = 35)
    private String idDevolucao;

    /**
     * EAGER de propósito: a resposta da API mostra o endToEndId do pagamento, e o controller monta a
     * resposta depois que a transação termina (open-in-view desligado). É um único pagamento por consulta.
     */
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "pagamento_id", nullable = false, updatable = false)
    private Pagamento pagamento;

    @Column(name = "valor", nullable = false, updatable = false, precision = 15, scale = 2)
    private BigDecimal valor;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private StatusDevolucao status;

    @Column(name = "motivo", updatable = false, length = 140)
    private String motivo;

    @Column(name = "solicitada_em", nullable = false, updatable = false)
    private Instant solicitadaEm;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    /** Exigido pelo JPA. */
    protected Devolucao() {
    }

    private Devolucao(String idDevolucao, Pagamento pagamento, BigDecimal valor, String motivo,
                      Instant solicitadaEm) {
        this.idDevolucao = Validacoes.idDevolucao(idDevolucao);
        this.pagamento = Validacoes.obrigatorio(pagamento, "pagamento");
        this.valor = Validacoes.valor(valor);
        this.motivo = Validacoes.motivo(motivo);
        this.solicitadaEm = Validacoes.obrigatorio(solicitadaEm, "solicitadaEm");
        this.status = StatusDevolucao.EM_PROCESSAMENTO;
    }

    /**
     * Construção no lado do domínio (testes e fixtures). Em produção a devolução é inserida por
     * INSERT ... ON CONFLICT DO NOTHING (ver DevolucaoRepository), que garante a idempotência.
     */
    public static Devolucao solicitar(String idDevolucao, Pagamento pagamento, BigDecimal valor,
                                      String motivo, Instant solicitadaEm) {
        return new Devolucao(idDevolucao, pagamento, valor, motivo, solicitadaEm);
    }

    /** EM_PROCESSAMENTO -> DEVOLVIDO. */
    public void concluir() {
        mudarPara(StatusDevolucao.DEVOLVIDO);
    }

    /** EM_PROCESSAMENTO -> NAO_REALIZADO. O estorno do saldo é feito pelo serviço, atomicamente no banco. */
    public void marcarNaoRealizada() {
        mudarPara(StatusDevolucao.NAO_REALIZADO);
    }

    private void mudarPara(StatusDevolucao novo) {
        if (status != StatusDevolucao.EM_PROCESSAMENTO) {
            throw new TransicaoInvalidaException("Devolução", idDevolucao, status, novo);
        }
        status = novo;
    }

    public Long getId() {
        return id;
    }

    public String getIdDevolucao() {
        return idDevolucao;
    }

    public Pagamento getPagamento() {
        return pagamento;
    }

    public BigDecimal getValor() {
        return valor;
    }

    public StatusDevolucao getStatus() {
        return status;
    }

    public String getMotivo() {
        return motivo;
    }

    public Instant getSolicitadaEm() {
        return solicitadaEm;
    }

    public long getVersion() {
        return version;
    }

    /** Igualdade pela chave de negócio (idDevolucao). Usa getter por causa dos proxies do Hibernate. */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Devolucao outra)) {
            return false;
        }
        return idDevolucao != null && idDevolucao.equals(outra.getIdDevolucao());
    }

    @Override
    public int hashCode() {
        return idDevolucao == null ? 0 : idDevolucao.hashCode();
    }
}
