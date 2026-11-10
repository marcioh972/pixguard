package br.com.pixguard.controller;

import br.com.pixguard.domain.Cobranca;
import br.com.pixguard.dto.CobrancaResponse;
import br.com.pixguard.dto.CriarCobrancaRequest;
import br.com.pixguard.service.CobrancaService;
import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@RestController
@RequestMapping("/api/v1/cobrancas")
public final class CobrancaController {

    private final CobrancaService service;

    public CobrancaController(CobrancaService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<CobrancaResponse> criar(@Valid @RequestBody CriarCobrancaRequest request) {
        // O regex do DTO já garantiu um decimal válido com 2 casas.
        Cobranca cobranca = service.criar(request.tipo(), new BigDecimal(request.valor()), request.dataVencimento());
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{txid}")
                .buildAndExpand(cobranca.getTxid())
                .toUri();
        return ResponseEntity.created(location).body(CobrancaResponse.de(cobranca));
    }

    @GetMapping("/{txid}")
    public CobrancaResponse buscar(@PathVariable String txid) {
        return CobrancaResponse.de(service.buscarPorTxid(txid));
    }
}
