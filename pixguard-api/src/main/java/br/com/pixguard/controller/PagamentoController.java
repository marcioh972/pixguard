package br.com.pixguard.controller;

import br.com.pixguard.domain.Devolucao;
import br.com.pixguard.dto.DevolucaoResponse;
import br.com.pixguard.dto.PaginaResponse;
import br.com.pixguard.dto.PadroesValidacao;
import br.com.pixguard.dto.PagamentoResponse;
import br.com.pixguard.dto.RegistrarDevolucaoRequest;
import br.com.pixguard.dto.ResultadoDevolucaoRequest;
import br.com.pixguard.exception.RequisicaoInvalidaException;
import br.com.pixguard.service.DevolucaoService;
import br.com.pixguard.service.PagamentoService;
import br.com.pixguard.service.ResultadoRegistro;
import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.net.URI;
import java.util.regex.Pattern;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@RestController
@RequestMapping("/api/v1/pagamentos")
public final class PagamentoController {

    private static final Pattern FORMATO_END_TO_END_ID = Pattern.compile(PadroesValidacao.END_TO_END_ID);
    private static final Pattern FORMATO_ID_DEVOLUCAO = Pattern.compile(PadroesValidacao.ID_DEVOLUCAO);
    private static final int TAMANHO_PADRAO_PAGINA = 20;
    private static final int TAMANHO_MAXIMO_PAGINA = 100;

    private final PagamentoService pagamentoService;
    private final DevolucaoService devolucaoService;

    public PagamentoController(PagamentoService pagamentoService, DevolucaoService devolucaoService) {
        this.pagamentoService = pagamentoService;
        this.devolucaoService = devolucaoService;
    }

    @GetMapping("/{endToEndId}")
    public PagamentoResponse buscar(@PathVariable String endToEndId) {
        return PagamentoResponse.de(pagamentoService.buscarPorEndToEndId(endToEndId(endToEndId)));
    }

    /** 201 quando a devolução é criada; 200 quando é a repetição de um pedido já registrado (idempotência). */
    @PutMapping("/{endToEndId}/devolucoes/{idDevolucao}")
    public ResponseEntity<DevolucaoResponse> solicitarDevolucao(
            @PathVariable String endToEndId,
            @PathVariable String idDevolucao,
            @Valid @RequestBody RegistrarDevolucaoRequest request) {
        ResultadoRegistro resultado = devolucaoService.registrar(
                endToEndId(endToEndId), idDevolucao(idDevolucao), new BigDecimal(request.valor()), request.motivo());
        DevolucaoResponse corpo = DevolucaoResponse.de(resultado.devolucao());
        if (!resultado.criada()) {
            return ResponseEntity.ok(corpo);
        }
        URI location = ServletUriComponentsBuilder.fromCurrentRequest().build().toUri();
        return ResponseEntity.created(location).body(corpo);
    }

    @GetMapping("/{endToEndId}/devolucoes/{idDevolucao}")
    public DevolucaoResponse buscarDevolucao(@PathVariable String endToEndId, @PathVariable String idDevolucao) {
        return DevolucaoResponse.de(devolucaoService.buscar(endToEndId(endToEndId), idDevolucao(idDevolucao)));
    }

    /** Paginada. A ordenação é fixa (por id): não aceitamos "sort" do cliente para não expor colunas internas. */
    @GetMapping("/{endToEndId}/devolucoes")
    public PaginaResponse<DevolucaoResponse> listarDevolucoes(
            @PathVariable String endToEndId,
            @RequestParam(defaultValue = "0") int pagina,
            @RequestParam(defaultValue = "" + TAMANHO_PADRAO_PAGINA) int tamanho) {
        PageRequest pageable = PageRequest.of(
                Math.max(0, pagina), Math.min(Math.max(1, tamanho), TAMANHO_MAXIMO_PAGINA), Sort.by("id"));
        return PaginaResponse.de(devolucaoService.listar(endToEndId(endToEndId), pageable), DevolucaoResponse::de);
    }

    /** Desfecho da devolução (DEVOLVIDO ou NAO_REALIZADO). Idempotente; NAO_REALIZADO estorna o saldo. */
    @PutMapping("/{endToEndId}/devolucoes/{idDevolucao}/resultado")
    public DevolucaoResponse registrarResultado(
            @PathVariable String endToEndId,
            @PathVariable String idDevolucao,
            @Valid @RequestBody ResultadoDevolucaoRequest request) {
        Devolucao devolucao = devolucaoService.registrarResultado(
                endToEndId(endToEndId), idDevolucao(idDevolucao), request.status());
        return DevolucaoResponse.de(devolucao);
    }

    private static String endToEndId(String valor) {
        if (!FORMATO_END_TO_END_ID.matcher(valor).matches()) {
            throw new RequisicaoInvalidaException("endToEndId deve ter 32 caracteres alfanuméricos");
        }
        return valor;
    }

    private static String idDevolucao(String valor) {
        if (!FORMATO_ID_DEVOLUCAO.matcher(valor).matches()) {
            throw new RequisicaoInvalidaException("idDevolucao deve ter de 1 a 35 caracteres alfanuméricos");
        }
        return valor;
    }
}
