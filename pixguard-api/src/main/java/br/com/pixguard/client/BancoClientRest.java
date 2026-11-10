package br.com.pixguard.client;

import br.com.pixguard.exception.BancoIndisponivelException;
import java.math.BigDecimal;
import java.time.Instant;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Implementação via RestClient. Os timeouts vêm da fábrica de requisições (ver BancoClientConfig).
 * Contrato: GET /mock/cobrancas/{txid} -> {"txid","status":"ATIVA|CONCLUIDA","pix":{...}}.
 * 404 NÃO é "não paga": pode ser URL errada ou banco que não conhece a cobrança, e expirar cobranças
 * com base num palpite seria pior do que não mudar nada. Vira falha, e nada é alterado.
 */
public class BancoClientRest implements BancoClient {

    private static final String STATUS_ATIVA = "ATIVA";
    private static final String STATUS_CONCLUIDA = "CONCLUIDA";

    private final RestClient restClient;

    public BancoClientRest(RestClient restClient) {
        this.restClient = restClient;
    }

    @Override
    public ConsultaCobranca consultarCobranca(String txid) {
        try {
            CobrancaResponse resposta = restClient.get()
                    .uri("/mock/cobrancas/{txid}", txid)
                    .retrieve()
                    .body(CobrancaResponse.class);
            return converter(txid, resposta);
        } catch (RestClientException | IllegalArgumentException e) {
            // IllegalArgumentException cobre valor/status inválidos na resposta (inclui NumberFormatException).
            throw new BancoIndisponivelException("Falha ao consultar o banco para o txid " + txid, e);
        }
    }

    private static ConsultaCobranca converter(String txid, CobrancaResponse resposta) {
        if (resposta == null || !txid.equals(resposta.txid())) {
            throw new IllegalArgumentException("resposta do banco ausente ou de outro txid");
        }
        if (STATUS_ATIVA.equals(resposta.status())) {
            return ConsultaCobranca.ativa(txid);
        }
        if (STATUS_CONCLUIDA.equals(resposta.status()) && resposta.pix() != null) {
            PixResponse pix = resposta.pix();
            return ConsultaCobranca.concluida(txid,
                    new PagamentoNoBanco(pix.endToEndId(), new BigDecimal(pix.valor()), pix.horario()));
        }
        throw new IllegalArgumentException("status do banco desconhecido ou incompleto: " + resposta.status());
    }

    record CobrancaResponse(String txid, String status, PixResponse pix) {
    }

    record PixResponse(String endToEndId, String valor, Instant horario) {
    }
}
