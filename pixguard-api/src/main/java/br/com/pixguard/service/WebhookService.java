package br.com.pixguard.service;

import br.com.pixguard.config.WebhookProperties;
import br.com.pixguard.exception.FalhaTransitoriaException;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Fachada NÃO transacional do webhook (nunca anotar com @Transactional). Cada tentativa de cada item roda na
 * transação do PagamentoService, OUTRO bean: @Transactional não vale em chamada interna da mesma classe, e um
 * retry dentro da mesma transação não funciona (a transação já está marcada para rollback e a leitura
 * continuaria vendo o estado velho).
 *
 * Quando um item perde uma disputa de concorrência (conflito de @Version, deadlock, ou o índice único de baixa),
 * ele é repetido do zero numa transação nova, até {@code maxRetentativas} vezes, com backoff curto.
 */
@Service
public class WebhookService {

    private static final Logger log = LoggerFactory.getLogger(WebhookService.class);

    private final PagamentoService pagamentoService;
    private final WebhookProperties propriedades;

    public WebhookService(PagamentoService pagamentoService, WebhookProperties propriedades) {
        this.pagamentoService = pagamentoService;
        this.propriedades = propriedades;
    }

    /**
     * Itens independentes: a falha de um não impede os demais. Se algum falhar de vez, a requisição inteira
     * responde 5xx para o banco reenviar; os itens já processados voltam como duplicados e são ignorados.
     */
    public void processar(List<PixRecebido> itens) {
        int falhas = 0;
        for (PixRecebido item : itens) {
            try {
                processarComRetentativas(item);
            } catch (RuntimeException e) {
                falhas++;
                log.error("evento=webhook_item_falhou txid={} endToEndId={}", item.txid(), item.endToEndId(), e);
            }
        }
        if (falhas > 0) {
            throw new FalhaTransitoriaException(falhas + " item(ns) não processado(s); reenvie o webhook");
        }
    }

    private void processarComRetentativas(PixRecebido item) {
        int retentativas = 0;
        while (true) {
            try {
                pagamentoService.processar(item);
                return;
            } catch (RuntimeException e) {
                if (!ConflitoDeConcorrencia.ehRetentavel(e)) {
                    throw e;
                }
                if (retentativas >= propriedades.maxRetentativas()) {
                    log.error("evento=webhook_retentativas_esgotadas txid={} endToEndId={} retentativas={}",
                            item.txid(), item.endToEndId(), retentativas);
                    throw e;
                }
                retentativas++;
                log.warn("evento=webhook_retentativa txid={} endToEndId={} tentativa={} motivo={}",
                        item.txid(), item.endToEndId(), retentativas, e.getClass().getSimpleName());
                esperar(retentativas);
            }
        }
    }

    private void esperar(int retentativa) {
        long baseMs = propriedades.backoffBase().toMillis();
        if (baseMs <= 0) {
            return;
        }
        long esperaMs = baseMs * retentativa + ThreadLocalRandom.current().nextLong(baseMs + 1);
        try {
            Thread.sleep(esperaMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new FalhaTransitoriaException("Processamento interrompido durante a retentativa");
        }
    }
}
