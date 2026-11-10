package br.com.pixguard.mockbank;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/** Envia o webhook no formato do BCB: {"pix":[{"endToEndId","txid","valor","horario"}]}. */
@Component
public class DespachanteWebhook {

    private static final Logger log = LoggerFactory.getLogger(DespachanteWebhook.class);

    private final RestClient restClient;
    private final MockBankProperties propriedades;

    public DespachanteWebhook(RestClient webhookClient, MockBankProperties propriedades) {
        this.restClient = webhookClient;
        this.propriedades = propriedades;
    }

    /** Nunca lança: a falha de entrega é parte da simulação e volta como texto no resultado. */
    public String enviar(PagamentoMock pagamento) {
        Item item = new Item(pagamento.endToEndId(), pagamento.txid(), pagamento.valor(),
                pagamento.horario().toString());
        try {
            int status = restClient.post()
                    .uri(propriedades.webhook())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new Payload(List.of(item)))
                    .retrieve()
                    .toBodilessEntity()
                    .getStatusCode()
                    .value();
            return "HTTP " + status;
        } catch (RestClientResponseException e) {
            return "HTTP " + e.getStatusCode().value();
        } catch (RestClientException e) {
            log.warn("evento=webhook_nao_entregue endToEndId={} motivo={}",
                    pagamento.endToEndId(), e.getClass().getSimpleName());
            return "ERRO: " + e.getClass().getSimpleName();
        }
    }

    // Records em vez de mapas: a ordem dos campos no JSON fica fixa, como no formato do BCB.
    record Payload(List<Item> pix) {
    }

    record Item(String endToEndId, String txid, String valor, String horario) {
    }
}
