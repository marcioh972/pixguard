package br.com.pixguard.exception;

/** O banco não respondeu de forma utilizável. Nunca deve levar a mudança de estado. */
public class BancoIndisponivelException extends RuntimeException {

    public BancoIndisponivelException(String mensagem) {
        super(mensagem);
    }

    public BancoIndisponivelException(String mensagem, Throwable causa) {
        super(mensagem, causa);
    }
}
