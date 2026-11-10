package br.com.pixguard.controller;

import br.com.pixguard.dto.WebhookPixRequest;
import br.com.pixguard.service.PixRecebido;
import br.com.pixguard.service.WebhookService;
import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 200 para processado E duplicado; 400 só para payload malformado; 5xx para falha transitória
 * (o banco reenvia).
 */
@RestController
@RequestMapping("/api/v1/webhooks/pix")
public final class WebhookPixController {

    private final WebhookService service;

    public WebhookPixController(WebhookService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.OK)
    public void receber(@Valid @RequestBody WebhookPixRequest request) {
        List<PixRecebido> itens = request.pix().stream()
                .map(i -> new PixRecebido(i.endToEndId(), i.txid(), new BigDecimal(i.valor()), i.horario()))
                .toList();
        service.processar(itens);
    }
}
