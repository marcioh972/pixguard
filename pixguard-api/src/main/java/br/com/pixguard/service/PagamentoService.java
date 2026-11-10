package br.com.pixguard.service;

import br.com.pixguard.domain.Pagamento;
import br.com.pixguard.domain.StatusPagamento;
import br.com.pixguard.exception.RecursoNaoEncontradoException;
import br.com.pixguard.repository.CobrancaRepository;
import br.com.pixguard.repository.PagamentoRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Processa UM pagamento recebido, em UMA transação. É o caminho único de baixa. */
@Service
public class PagamentoService {

    private static final Logger log = LoggerFactory.getLogger(PagamentoService.class);

    private final PagamentoRepository pagamentoRepository;
    private final CobrancaRepository cobrancaRepository;
    private final Conciliador conciliador;
    private final Clock clock;

    public PagamentoService(PagamentoRepository pagamentoRepository, CobrancaRepository cobrancaRepository,
                            Conciliador conciliador, Clock clock) {
        this.pagamentoRepository = pagamentoRepository;
        this.cobrancaRepository = cobrancaRepository;
        this.conciliador = conciliador;
        this.clock = clock;
    }

    @Transactional
    public ResultadoProcessamento processar(PixRecebido pix) {
        try (ContextoLog contexto = ContextoLog.de(pix.txid(), pix.endToEndId())) {
            Instant agora = clock.instant();

            boolean novo = pagamentoRepository.registrarSeNovo(
                    pix.endToEndId(), pix.txid(), pix.valor(), pix.pagoEm(), agora);
            if (!novo) {
                log.info("evento=pagamento_duplicado");
                return ResultadoProcessamento.DUPLICADO;
            }

            Pagamento pagamento = pagamentoRepository.findByEndToEndId(pix.endToEndId())
                    .orElseThrow(() -> new IllegalStateException(
                            "Pagamento recém-inserido não encontrado: " + pix.endToEndId()));

            return cobrancaRepository.findByTxid(pix.txid())
                    .map(cobranca -> conciliador.conciliar(pagamento, cobranca, agora))
                    .orElseGet(() -> {
                        log.info("evento=pagamento_aguardando_cobranca");
                        return ResultadoProcessamento.AGUARDANDO_COBRANCA;
                    });
        }
    }

    /**
     * Rede de segurança do job: reprocessa UM pagamento AGUARDANDO_COBRANCA cuja cobrança já existe, pelo
     * mesmo Conciliador do webhook. Trava a linha (SKIP LOCKED): se outro processo já está nela, ignora.
     */
    @Transactional
    public ResultadoProcessamento reprocessarAguardando(Long pagamentoId) {
        Optional<Pagamento> bloqueado = pagamentoRepository.bloquearAguardando(
                pagamentoId, StatusPagamento.AGUARDANDO_COBRANCA.name());
        if (bloqueado.isEmpty()) {
            return ResultadoProcessamento.IGNORADO;
        }
        Pagamento pagamento = bloqueado.get();
        try (ContextoLog contexto = ContextoLog.de(pagamento.getTxid(), pagamento.getEndToEndId())) {
            Instant agora = clock.instant();
            return cobrancaRepository.findByTxid(pagamento.getTxid())
                    .map(cobranca -> conciliador.conciliar(pagamento, cobranca, agora))
                    .orElse(ResultadoProcessamento.IGNORADO);
        }
    }

    @Transactional(readOnly = true)
    public Pagamento buscarPorEndToEndId(String endToEndId) {
        return pagamentoRepository.findByEndToEndId(endToEndId)
                .orElseThrow(() -> new RecursoNaoEncontradoException("Pagamento não encontrado: " + endToEndId));
    }
}
