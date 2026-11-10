package br.com.pixguard.exception;

import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Todos os erros saem como ProblemDetail (RFC 9457).
 * 400 payload inválido, 404 não encontrado, 409 conflito (etapa 5), 422 regra de negócio.
 * A base ResponseEntityExceptionHandler já cobre JSON malformado, tipo errado, rota inexistente etc.
 */
@RestControllerAdvice
public class TratadorDeErros extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(TratadorDeErros.class);

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
                                                                  HttpHeaders headers,
                                                                  HttpStatusCode status,
                                                                  WebRequest request) {
        ProblemDetail problema = problema(HttpStatus.BAD_REQUEST, "Requisição inválida", "Payload inválido");
        List<Map<String, String>> erros = ex.getBindingResult().getAllErrors().stream()
                .map(TratadorDeErros::detalhar)
                .toList();
        problema.setProperty("erros", erros);
        return handleExceptionInternal(ex, problema, headers, status, request);
    }

    @ExceptionHandler(RecursoNaoEncontradoException.class)
    public ProblemDetail naoEncontrado(RecursoNaoEncontradoException ex) {
        return problema(HttpStatus.NOT_FOUND, "Recurso não encontrado", ex.getMessage());
    }

    @ExceptionHandler({RegraDeNegocioException.class, TransicaoInvalidaException.class})
    public ProblemDetail regraDeNegocio(RuntimeException ex) {
        return problema(HttpStatus.UNPROCESSABLE_ENTITY, "Regra de negócio violada", ex.getMessage());
    }

    @ExceptionHandler(ConflitoException.class)
    public ProblemDetail conflito(ConflitoException ex) {
        return problema(HttpStatus.CONFLICT, "Conflito", ex.getMessage());
    }

    @ExceptionHandler(RequisicaoInvalidaException.class)
    public ProblemDetail requisicaoInvalida(RequisicaoInvalidaException ex) {
        return problema(HttpStatus.BAD_REQUEST, "Requisição inválida", ex.getMessage());
    }

    /**
     * Disputa de concorrência que escapou dos pontos que fazem retry (criação de cobrança, devoluções):
     * o recurso mudou por baixo da requisição. 409: o cliente pode repetir.
     */
    @ExceptionHandler(ConcurrencyFailureException.class)
    public ProblemDetail conflitoDeConcorrencia(ConcurrencyFailureException ex) {
        log.warn("evento=conflito_de_concorrencia motivo={}", ex.getClass().getSimpleName());
        return problema(HttpStatus.CONFLICT, "Conflito de concorrência",
                "O recurso foi alterado por outra requisição. Repita a operação.");
    }

    /** 5xx de propósito: o banco de origem reenvia o webhook, e a idempotência absorve a repetição. */
    @ExceptionHandler(FalhaTransitoriaException.class)
    public ProblemDetail falhaTransitoria(FalhaTransitoriaException ex) {
        return problema(HttpStatus.SERVICE_UNAVAILABLE, "Falha transitória", ex.getMessage());
    }

    /** Rede de segurança: nunca vaza detalhe interno; o stack trace vai para o log. */
    @ExceptionHandler(Exception.class)
    public ProblemDetail inesperado(Exception ex) {
        log.error("evento=erro_inesperado", ex);
        return problema(HttpStatus.INTERNAL_SERVER_ERROR, "Erro interno", "Erro inesperado. Tente novamente.");
    }

    private static ProblemDetail problema(HttpStatus status, String titulo, String detalhe) {
        ProblemDetail problema = ProblemDetail.forStatusAndDetail(status, detalhe);
        problema.setTitle(titulo);
        return problema;
    }

    private static Map<String, String> detalhar(ObjectError erro) {
        String campo = erro instanceof FieldError fe ? fe.getField() : erro.getObjectName();
        return Map.of("campo", campo, "mensagem", String.valueOf(erro.getDefaultMessage()));
    }
}
