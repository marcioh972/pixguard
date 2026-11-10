package br.com.pixguard.service;

import br.com.pixguard.config.CobrancaProperties;
import br.com.pixguard.domain.Cobranca;
import br.com.pixguard.domain.Pagamento;
import br.com.pixguard.domain.StatusPagamento;
import br.com.pixguard.domain.TipoCobranca;
import br.com.pixguard.exception.RecursoNaoEncontradoException;
import br.com.pixguard.exception.RegraDeNegocioException;
import br.com.pixguard.repository.CobrancaRepository;
import br.com.pixguard.repository.PagamentoRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// Não é final: o Spring precisa criar um proxy (CGLIB) para aplicar @Transactional.
@Service
public class CobrancaService {

    private static final Logger log = LoggerFactory.getLogger(CobrancaService.class);

    private final CobrancaRepository repository;
    private final PagamentoRepository pagamentoRepository;
    private final Conciliador conciliador;
    private final GeradorTxid geradorTxid;
    private final Clock clock;
    private final CobrancaProperties propriedades;

    public CobrancaService(CobrancaRepository repository, PagamentoRepository pagamentoRepository,
                           Conciliador conciliador, GeradorTxid geradorTxid, Clock clock,
                           CobrancaProperties propriedades) {
        this.repository = repository;
        this.pagamentoRepository = pagamentoRepository;
        this.conciliador = conciliador;
        this.geradorTxid = geradorTxid;
        this.clock = clock;
        this.propriedades = propriedades;
    }

    @Transactional
    public Cobranca criar(TipoCobranca tipo, BigDecimal valor, LocalDate dataVencimento) {
        Instant criadaEm = clock.instant();
        String txid = geradorTxid.gerar();

        Cobranca cobranca = switch (tipo) {
            case IMEDIATA -> Cobranca.criarImediata(
                    txid, valor, criadaEm, criadaEm.plus(propriedades.expiracaoPadrao()));
            case COM_VENCIMENTO -> Cobranca.criarComVencimento(
                    txid, valor, dataVencimento, criadaEm, calcularExpiracao(dataVencimento, criadaEm));
        };

        Cobranca salva = repository.save(cobranca);
        log.info("evento=cobranca_criada txid={} tipo={}", salva.getTxid(), salva.getTipo());

        // Mesma transação: ou a cobrança nasce já conciliada com o que chegou antes, ou não nasce.
        conciliarPagamentosAguardando(salva, criadaEm);
        return salva;
    }

    @Transactional(readOnly = true)
    public Cobranca buscarPorTxid(String txid) {
        return repository.findByTxid(txid)
                .orElseThrow(() -> new RecursoNaoEncontradoException("Cobrança não encontrada: " + txid));
    }

    /** Pagamentos que chegaram antes da cobrança: o mais antigo (pelo horário do banco) é o válido. */
    private void conciliarPagamentosAguardando(Cobranca cobranca, Instant agora) {
        List<Pagamento> esperando = pagamentoRepository.findByTxidAndStatusOrderByPagoEmAscIdAsc(
                cobranca.getTxid(), StatusPagamento.AGUARDANDO_COBRANCA);
        for (Pagamento pagamento : esperando) {
            try (ContextoLog contexto = ContextoLog.de(cobranca.getTxid(), pagamento.getEndToEndId())) {
                conciliador.conciliar(pagamento, cobranca, agora);
            }
        }
    }

    /**
     * expira_em = fim do dia do vencimento + validadeAposVencimentoDias, no fuso configurado.
     * "Fim do dia" é o início do dia seguinte (limite exclusivo), calculado em data local
     * para não errar em mudança de horário de verão.
     */
    private Instant calcularExpiracao(LocalDate dataVencimento, Instant criadaEm) {
        if (dataVencimento == null) {
            throw new IllegalArgumentException("dataVencimento é obrigatória para COM_VENCIMENTO");
        }
        LocalDate hoje = LocalDate.ofInstant(criadaEm, propriedades.fusoVencimento());
        if (dataVencimento.isBefore(hoje)) {
            throw new RegraDeNegocioException("dataVencimento não pode estar no passado");
        }
        long dias = propriedades.validadeAposVencimentoDias() + 1L;
        return dataVencimento.plusDays(dias).atStartOfDay(propriedades.fusoVencimento()).toInstant();
    }
}
