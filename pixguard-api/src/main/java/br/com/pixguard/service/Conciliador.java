package br.com.pixguard.service;

import br.com.pixguard.domain.Cobranca;
import br.com.pixguard.domain.Pagamento;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * ÚNICO lugar que decide o desfecho de um pagamento que já tem cobrança. Webhook, criação de
 * cobrança e job (etapa 4) passam por aqui; por isso só existe um caminho de baixa.
 * Sem transação própria: roda dentro da transação de quem chama. O contexto de log (MDC)
 * também é aberto por quem chama.
 */
@Component
public class Conciliador {

    private static final Logger log = LoggerFactory.getLogger(Conciliador.class);

    public ResultadoProcessamento conciliar(Pagamento pagamento, Cobranca cobranca, Instant agora) {
        return switch (cobranca.getStatus()) {
            // Já paga (e talvez devolvida): este é dinheiro a mais, nunca uma segunda baixa.
            case PAGA, DEVOLVIDA_PARCIAL, DEVOLVIDA_TOTAL -> {
                pagamento.marcarExcedente(cobranca);
                log.info("evento=pagamento_excedente");
                yield ResultadoProcessamento.EXCEDENTE;
            }
            case ATIVA, EXPIRADA -> {
                // A validade se decide pelo horário do PAGAMENTO, não pela hora do processamento:
                // o job pode ter expirado a cobrança antes de um webhook atrasado chegar.
                if (pagamento.getPagoEm().isAfter(cobranca.getExpiraEm())) {
                    pagamento.marcarRecebidoAposExpiracao(cobranca);
                    log.info("evento=pagamento_apos_expiracao");
                    yield ResultadoProcessamento.RECEBIDO_APOS_EXPIRACAO;
                }
                cobranca.marcarComoPaga(agora);
                pagamento.conciliar(cobranca);
                log.info("evento=pagamento_baixado");
                yield ResultadoProcessamento.CONCILIADO;
            }
        };
    }
}
