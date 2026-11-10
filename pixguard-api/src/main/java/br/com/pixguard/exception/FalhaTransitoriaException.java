package br.com.pixguard.exception;

/** Falha que pode se resolver sozinha numa nova tentativa (HTTP 503: o banco reenvia o webhook). */
public class FalhaTransitoriaException extends RuntimeException {

    public FalhaTransitoriaException(String mensagem) {
        super(mensagem);
    }
}
