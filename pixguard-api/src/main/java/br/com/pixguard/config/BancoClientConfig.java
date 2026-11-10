package br.com.pixguard.config;

import br.com.pixguard.client.BancoClient;
import br.com.pixguard.client.BancoClientRest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
public class BancoClientConfig {

    @Bean
    BancoClient bancoClient(RestClient.Builder builder, BancoProperties propriedades) {
        SimpleClientHttpRequestFactory fabrica = new SimpleClientHttpRequestFactory();
        fabrica.setConnectTimeout((int) propriedades.timeoutConexao().toMillis());
        fabrica.setReadTimeout((int) propriedades.timeoutLeitura().toMillis());
        RestClient restClient = builder
                .baseUrl(propriedades.url())
                .requestFactory(fabrica)
                .build();
        return new BancoClientRest(restClient);
    }
}
