package br.com.pixguard.service;

import br.com.pixguard.client.ConsultaCobranca;
import br.com.pixguard.client.PagamentoNoBanco;
import br.com.pixguard.config.JobProperties;
import br.com.pixguard.domain.Cobranca;
import br.com.pixguard.domain.StatusCobranca;
import br.com.pixguard.repository.CobrancaRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Aplica o resultado da consulta ao banco a UMA cobrança, em sua própria transação: uma falha aqui
 * desfaz só esta cobrança e não derruba o lote. A consulta HTTP é feita ANTES, fora da transação,
 * para não segurar lock de linha durante uma chamada de rede.
 */
@Service
public class ExpiracaoCobrancaService {

    private static final Logger log = LoggerFactory.getLogger(ExpiracaoCobrancaService.class);

    private final CobrancaRepository cobrancaRepository;
    private final PagamentoService pagamentoService;
    private final JobProperties job;
    private final Clock clock;

    public ExpiracaoCobrancaService(CobrancaRepository cobrancaRepository, PagamentoService pagamentoService,
                                    JobProperties job, Clock clock) {
        this.cobrancaRepository = cobrancaRepository;
        this.pagamentoService = pagamentoService;
        this.job = job;
        this.clock = clock;
    }

    @Transactional
    public ResultadoCobranca aplicar(Long cobrancaId, ConsultaCobranca consulta) {
        Instant agora = clock.instant();
        Instant limite = agora.minus(job.toleranciaExpiracao());

        Optional<Cobranca> bloqueada = cobrancaRepository.bloquearAtivaVencida(
                cobrancaId, StatusCobranca.ATIVA.name(), limite);
        if (bloqueada.isEmpty()) {
            return ResultadoCobranca.IGNORADA;
        }
        Cobranca cobranca = bloqueada.get();

        if (consulta.paga()) {
            // MESMO serviço do webhook: um único caminho de baixa. Se o pagamento já existia, vira DUPLICADO.
            PagamentoNoBanco pago = consulta.pagamento();
            pagamentoService.processar(
                    new PixRecebido(pago.endToEndId(), cobranca.getTxid(), pago.valor(), pago.horario()));
        }

        // Continua ATIVA e já passou do prazo: ou o banco não registrou pagamento, ou o pagamento foi
        // depois do prazo (RECEBIDO_APOS_EXPIRACAO, que não muda a cobrança). Em ambos, expira.
        if (cobranca.getStatus() == StatusCobranca.ATIVA) {
            cobranca.expirar(agora);
            log.info("evento=cobranca_expirada");
        }
        return cobranca.getStatus() == StatusCobranca.PAGA ? ResultadoCobranca.BAIXADA : ResultadoCobranca.EXPIRADA;
    }
}
