package br.com.pixguard.service;

import br.com.pixguard.client.BancoClient;
import br.com.pixguard.client.ConsultaCobranca;
import br.com.pixguard.config.JobProperties;
import br.com.pixguard.domain.Cobranca;
import br.com.pixguard.domain.Pagamento;
import br.com.pixguard.domain.StatusCobranca;
import br.com.pixguard.domain.StatusPagamento;
import br.com.pixguard.exception.BancoIndisponivelException;
import br.com.pixguard.repository.CobrancaRepository;
import br.com.pixguard.repository.PagamentoRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

/**
 * Orquestra um ciclo do job. NÃO é transacional de propósito: cada item roda na transação do bean
 * que o processa (PagamentoService, ExpiracaoCobrancaService), então a falha de um item desfaz só ele.
 * Públicos para os testes chamarem sem esperar o agendador.
 */
@Service
public class ReconciliacaoService {

    private static final Logger log = LoggerFactory.getLogger(ReconciliacaoService.class);

    private final CobrancaRepository cobrancaRepository;
    private final PagamentoRepository pagamentoRepository;
    private final BancoClient bancoClient;
    private final ExpiracaoCobrancaService expiracaoService;
    private final PagamentoService pagamentoService;
    private final JobProperties job;
    private final Clock clock;

    public ReconciliacaoService(CobrancaRepository cobrancaRepository, PagamentoRepository pagamentoRepository,
                                BancoClient bancoClient, ExpiracaoCobrancaService expiracaoService,
                                PagamentoService pagamentoService, JobProperties job, Clock clock) {
        this.cobrancaRepository = cobrancaRepository;
        this.pagamentoRepository = pagamentoRepository;
        this.bancoClient = bancoClient;
        this.expiracaoService = expiracaoService;
        this.pagamentoService = pagamentoService;
        this.job = job;
        this.clock = clock;
    }

    /** Pagamentos primeiro: é barato e pode resolver sozinho cobranças que o banco nem precisaria ser consultado. */
    public ResumoCiclo executarCiclo() {
        ResumoPagamentos pagamentos = reprocessarPagamentosAguardando();
        ResumoCobrancas cobrancas = processarCobrancasSemRetorno();
        if (pagamentos.houveAtividade() || cobrancas.houveAtividade()) {
            log.info("evento=job_ciclo_concluido pagamentosReprocessados={} pagamentosIgnorados={} "
                            + "pagamentosFalhas={} cobrancasBaixadas={} cobrancasExpiradas={} "
                            + "cobrancasIgnoradas={} cobrancasFalhas={}",
                    pagamentos.reprocessados(), pagamentos.ignorados(), pagamentos.falhas(),
                    cobrancas.baixadas(), cobrancas.expiradas(), cobrancas.ignoradas(), cobrancas.falhas());
        }
        return new ResumoCiclo(pagamentos, cobrancas);
    }

    /** (b) Fecha a corrida em que webhook e criação da cobrança aconteceram juntos e nenhum viu o outro. */
    public ResumoPagamentos reprocessarPagamentosAguardando() {
        List<Pagamento> lote = pagamentoRepository.listarAguardandoComCobranca(
                StatusPagamento.AGUARDANDO_COBRANCA, PageRequest.ofSize(job.tamanhoLote()));

        int reprocessados = 0;
        int ignorados = 0;
        int falhas = 0;
        for (Pagamento pagamento : lote) {
            try (ContextoLog contexto = ContextoLog.de(pagamento.getTxid(), pagamento.getEndToEndId())) {
                ResultadoProcessamento resultado = pagamentoService.reprocessarAguardando(pagamento.getId());
                if (resultado == ResultadoProcessamento.IGNORADO) {
                    ignorados++;
                } else {
                    reprocessados++;
                }
            } catch (RuntimeException e) {
                falhas++;
                log.error("evento=reprocessamento_falhou txid={} endToEndId={}",
                        pagamento.getTxid(), pagamento.getEndToEndId(), e);
            }
        }
        return new ResumoPagamentos(reprocessados, ignorados, falhas);
    }

    /**
     * (a) Cobranças ATIVAS "sem retorno": passaram de expira_em + tolerância sem baixa. Pergunta ao banco;
     * se pagou, baixa pelo caminho único; senão expira. Erro do banco: loga e não altera nada.
     */
    public ResumoCobrancas processarCobrancasSemRetorno() {
        Instant limite = clock.instant().minus(job.toleranciaExpiracao());
        List<Cobranca> lote = cobrancaRepository.findByStatusAndExpiraEmLessThanEqualOrderByExpiraEmAscIdAsc(
                StatusCobranca.ATIVA, limite, PageRequest.ofSize(job.tamanhoLote()));

        int baixadas = 0;
        int expiradas = 0;
        int ignoradas = 0;
        int falhas = 0;
        for (Cobranca cobranca : lote) {
            try (ContextoLog contexto = ContextoLog.de(cobranca.getTxid())) {
                ConsultaCobranca consulta = bancoClient.consultarCobranca(cobranca.getTxid());
                ResultadoCobranca resultado = expiracaoService.aplicar(cobranca.getId(), consulta);
                switch (resultado) {
                    case BAIXADA -> baixadas++;
                    case EXPIRADA -> expiradas++;
                    case IGNORADA -> ignoradas++;
                }
            } catch (BancoIndisponivelException e) {
                falhas++;
                log.warn("evento=banco_indisponivel txid={} motivo={}", cobranca.getTxid(), e.getMessage());
            } catch (RuntimeException e) {
                falhas++;
                log.error("evento=cobranca_sem_retorno_falhou txid={}", cobranca.getTxid(), e);
            }
        }
        return new ResumoCobrancas(baixadas, expiradas, ignoradas, falhas);
    }
}
