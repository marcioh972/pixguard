package br.com.pixguard.mockbank;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/mock")
public final class MockBankController {

    private final BancoSimulado banco;

    public MockBankController(BancoSimulado banco) {
        this.banco = banco;
    }

    /** 200 quando os webhooks já foram enviados; 202 no modo ATRASADO (ainda vão sair). */
    @PostMapping("/pix/pagar")
    public ResponseEntity<ResultadoPagar> pagar(@Valid @RequestBody PagarRequest pedido) {
        ResultadoPagar resultado = banco.pagar(pedido);
        return ResponseEntity.status(resultado.agendado() ? HttpStatus.ACCEPTED : HttpStatus.OK).body(resultado);
    }

    /** Qualquer txid sem pagamento registrado é ATIVA: o banco "conhece" todas as cobranças. */
    @GetMapping("/cobrancas/{txid}")
    public CobrancaBancoResponse consultar(@PathVariable String txid) {
        return banco.consultar(txid)
                .map(CobrancaBancoResponse::concluida)
                .orElseGet(() -> CobrancaBancoResponse.ativa(txid));
    }

    /** Zera o estado em memória (útil entre demonstrações). */
    @DeleteMapping("/pagamentos")
    public ResponseEntity<Void> limpar() {
        banco.limpar();
        return ResponseEntity.noContent().build();
    }
}
