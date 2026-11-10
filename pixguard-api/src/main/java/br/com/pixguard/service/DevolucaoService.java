package br.com.pixguard.service;

import br.com.pixguard.config.DevolucaoProperties;
import br.com.pixguard.domain.Devolucao;
import br.com.pixguard.domain.Pagamento;
import br.com.pixguard.domain.StatusDevolucao;
import br.com.pixguard.domain.StatusPagamento;
import br.com.pixguard.exception.ConflitoException;
import br.com.pixguard.exception.RecursoNaoEncontradoException;
import br.com.pixguard.exception.RegraDeNegocioException;
import br.com.pixguard.repository.DevolucaoRepository;
import br.com.pixguard.repository.PagamentoRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Devoluções. A garantia de saldo é do BANCO (UPDATE atômico condicional + CHECK); o código só trata
 * os desfechos. Toda a operação roda numa transação: qualquer falha desfaz a devolução inserida.
 */
@Service
public class DevolucaoService {

    private static final Logger log = LoggerFactory.getLogger(DevolucaoService.class);

    private final PagamentoRepository pagamentoRepository;
    private final DevolucaoRepository devolucaoRepository;
    private final DevolucaoProperties propriedades;
    private final Clock clock;

    public DevolucaoService(PagamentoRepository pagamentoRepository, DevolucaoRepository devolucaoRepository,
                            DevolucaoProperties propriedades, Clock clock) {
        this.pagamentoRepository = pagamentoRepository;
        this.devolucaoRepository = devolucaoRepository;
        this.propriedades = propriedades;
        this.clock = clock;
    }

    /**
     * Idempotente pelo idDevolucao. Ordem: (1) pagamento existe; (2) INSERT ... ON CONFLICT DO NOTHING, que
     * separa repetição de pedido novo; (3) só para pedido NOVO: elegibilidade, prazo e saldo. Qualquer
     * recusa em (3) lança exceção e o rollback desfaz o INSERT de (2).
     * Repetição é respondida antes das regras de prazo e saldo: reenviar um pedido que já deu certo
     * nunca pode virar erro só porque o prazo passou nesse meio tempo.
     */
    @Transactional
    public ResultadoRegistro registrar(String endToEndId, String idDevolucao, BigDecimal valor, String motivo) {
        Pagamento pagamento = buscarPagamento(endToEndId);
        try (ContextoLog contexto = ContextoLog.de(pagamento.getTxid(), pagamento.getEndToEndId())) {
            Instant agora = clock.instant();

            boolean nova = devolucaoRepository.registrarSeNova(idDevolucao, pagamento.getId(), valor, motivo, agora);
            if (!nova) {
                return repeticao(pagamento, idDevolucao, valor);
            }

            exigirElegivel(pagamento);
            exigirDentroDoPrazo(pagamento, agora);

            if (pagamentoRepository.reservarSaldo(pagamento.getId(), valor) == 0) {
                throw new RegraDeNegocioException("Saldo insuficiente para devolver " + valor.toPlainString());
            }
            refletirNaCobranca(pagamento, agora);

            log.info("evento=devolucao_registrada idDevolucao={}", idDevolucao);
            return new ResultadoRegistro(buscarDevolucao(idDevolucao), true);
        }
    }

    /**
     * Registra o desfecho de uma devolução EM_PROCESSAMENTO (PUT idempotente). NAO_REALIZADO estorna o saldo.
     * Repetir o mesmo desfecho é inofensivo; pedir o desfecho contrário de um já registrado é conflito (409).
     */
    @Transactional
    public Devolucao registrarResultado(String endToEndId, String idDevolucao, StatusDevolucao resultado) {
        if (resultado == StatusDevolucao.EM_PROCESSAMENTO) {
            throw new IllegalArgumentException("o resultado deve ser DEVOLVIDO ou NAO_REALIZADO");
        }
        Devolucao devolucao = devolucaoRepository.buscarParaAtualizar(idDevolucao)
                .filter(d -> d.getPagamento().getEndToEndId().equals(endToEndId))
                .orElseThrow(() -> naoEncontrada(endToEndId, idDevolucao));
        Pagamento pagamento = devolucao.getPagamento();

        try (ContextoLog contexto = ContextoLog.de(pagamento.getTxid(), pagamento.getEndToEndId())) {
            if (devolucao.getStatus() == resultado) {
                return devolucao;
            }
            if (devolucao.getStatus() != StatusDevolucao.EM_PROCESSAMENTO) {
                throw new ConflitoException("A devolução %s já está %s e não pode virar %s"
                        .formatted(idDevolucao, devolucao.getStatus(), resultado));
            }

            if (resultado == StatusDevolucao.DEVOLVIDO) {
                devolucao.concluir();
                log.info("evento=devolucao_concluida idDevolucao={}", idDevolucao);
            } else {
                devolucao.marcarNaoRealizada();
                if (pagamentoRepository.estornarSaldo(pagamento.getId(), devolucao.getValor()) == 0) {
                    // Não deveria acontecer: o saldo devolvido sempre inclui as devoluções não finalizadas.
                    throw new IllegalStateException("Saldo devolvido menor que a devolução a estornar: " + idDevolucao);
                }
                refletirNaCobranca(pagamento, clock.instant());
                log.info("evento=devolucao_nao_realizada idDevolucao={}", idDevolucao);
            }
            return devolucao;
        }
    }

    @Transactional(readOnly = true)
    public Devolucao buscar(String endToEndId, String idDevolucao) {
        return devolucaoRepository.findByIdDevolucao(idDevolucao)
                .filter(d -> d.getPagamento().getEndToEndId().equals(endToEndId))
                .orElseThrow(() -> naoEncontrada(endToEndId, idDevolucao));
    }

    @Transactional(readOnly = true)
    public Page<Devolucao> listar(String endToEndId, Pageable pageable) {
        Pagamento pagamento = buscarPagamento(endToEndId);
        return devolucaoRepository.findByPagamentoId(pagamento.getId(), pageable);
    }

    private ResultadoRegistro repeticao(Pagamento pagamento, String idDevolucao, BigDecimal valor) {
        Devolucao existente = buscarDevolucao(idDevolucao);
        boolean mesmoPagamento = existente.getPagamento().getId().equals(pagamento.getId());
        if (mesmoPagamento && existente.getValor().compareTo(valor) == 0) {
            log.info("evento=devolucao_repetida idDevolucao={}", idDevolucao);
            return new ResultadoRegistro(existente, false);
        }
        throw new ConflitoException(
                "O idDevolucao %s já foi usado com outro valor ou para outro pagamento".formatted(idDevolucao));
    }

    /**
     * Só pagamentos já resolvidos podem ser devolvidos. AGUARDANDO_COBRANCA não: se a cobrança aparecesse
     * depois, ela seria baixada por um pagamento que já foi (em parte) devolvido.
     */
    private static void exigirElegivel(Pagamento pagamento) {
        if (pagamento.getStatus() == StatusPagamento.AGUARDANDO_COBRANCA) {
            throw new RegraDeNegocioException("O pagamento ainda não foi conciliado com uma cobrança");
        }
    }

    /** agora - pago_em <= prazo (limite inclusivo). O prazo conta do horário do pagamento, não do processamento. */
    private void exigirDentroDoPrazo(Pagamento pagamento, Instant agora) {
        if (Duration.between(pagamento.getPagoEm(), agora).compareTo(propriedades.prazo()) > 0) {
            throw new RegraDeNegocioException("Prazo de devolução expirado");
        }
    }

    /**
     * Só o pagamento que BAIXOU a cobrança (CONCILIADO) afeta o status dela. Devolver um EXCEDENTE ou um
     * RECEBIDO_APOS_EXPIRACAO não muda a cobrança: ela continua paga (ou expirada) como estava.
     * O saldo é relido do banco depois do UPDATE atômico, e a cobrança só é carregada agora, depois de
     * qualquer espera por outra devolução na mesma linha: nunca opera sobre estado velho.
     */
    private void refletirNaCobranca(Pagamento pagamento, Instant agora) {
        if (pagamento.getStatus() != StatusPagamento.CONCILIADO) {
            return;
        }
        BigDecimal devolvido = pagamentoRepository.buscarValorDevolvido(pagamento.getId());
        pagamento.getCobranca().atualizarPorDevolucao(devolvido, pagamento.getValor(), agora);
    }

    private Pagamento buscarPagamento(String endToEndId) {
        return pagamentoRepository.findByEndToEndId(endToEndId)
                .orElseThrow(() -> new RecursoNaoEncontradoException("Pagamento não encontrado: " + endToEndId));
    }

    private Devolucao buscarDevolucao(String idDevolucao) {
        return devolucaoRepository.findByIdDevolucao(idDevolucao)
                .orElseThrow(() -> new IllegalStateException("Devolução inexistente após o insert: " + idDevolucao));
    }

    private static RecursoNaoEncontradoException naoEncontrada(String endToEndId, String idDevolucao) {
        return new RecursoNaoEncontradoException(
                "Devolução %s não encontrada para o pagamento %s".formatted(idDevolucao, endToEndId));
    }
}
