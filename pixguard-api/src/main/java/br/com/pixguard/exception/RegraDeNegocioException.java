package br.com.pixguard.exception;

/** Requisição bem formada, mas que viola uma regra de negócio (HTTP 422). */
public class RegraDeNegocioException extends RuntimeException {

    public RegraDeNegocioException(String mensagem) {
        super(mensagem);
    }
}
