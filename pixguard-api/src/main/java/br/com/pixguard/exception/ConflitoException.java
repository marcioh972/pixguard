package br.com.pixguard.exception;

/** O pedido conflita com o que já existe, ex.: mesmo idDevolucao com outro valor (HTTP 409). */
public class ConflitoException extends RuntimeException {

    public ConflitoException(String mensagem) {
        super(mensagem);
    }
}
