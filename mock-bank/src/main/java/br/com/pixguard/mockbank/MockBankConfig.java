package br.com.pixguard.mockbank;

import java.time.Clock;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
class MockBankConfig {

    private static final int THREADS_DE_ENTREGA = 2;

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    RestClient webhookClient(RestClient.Builder builder, MockBankProperties propriedades) {
        SimpleClientHttpRequestFactory fabrica = new SimpleClientHttpRequestFactory();
        fabrica.setConnectTimeout((int) propriedades.timeoutConexao().toMillis());
        fabrica.setReadTimeout((int) propriedades.timeoutLeitura().toMillis());
        return builder.requestFactory(fabrica).build();
    }

    /** Entregas atrasadas (modo ATRASADO). Encerrado junto com o contexto. */
    @Bean(destroyMethod = "shutdownNow")
    ScheduledExecutorService agendadorDeEntregas() {
        return Executors.newScheduledThreadPool(THREADS_DE_ENTREGA);
    }
}
