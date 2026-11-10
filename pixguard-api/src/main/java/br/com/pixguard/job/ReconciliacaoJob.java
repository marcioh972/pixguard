package br.com.pixguard.job;

import br.com.pixguard.service.ReconciliacaoService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Só agenda. Toda a lógica está em ReconciliacaoService, que os testes chamam direto.
 * fixedDelay (e não fixedRate): o próximo ciclo só começa depois que o anterior termina, sem sobreposição.
 * O primeiro ciclo espera um intervalo, para a aplicação terminar de subir (migrações, pool).
 */
@Component
@ConditionalOnProperty(prefix = "pixguard.job", name = "habilitado", havingValue = "true")
public class ReconciliacaoJob {

    private final ReconciliacaoService servico;

    public ReconciliacaoJob(ReconciliacaoService servico) {
        this.servico = servico;
    }

    @Scheduled(fixedDelayString = "${pixguard.job.intervalo}", initialDelayString = "${pixguard.job.intervalo}")
    public void executar() {
        servico.executarCiclo();
    }
}
