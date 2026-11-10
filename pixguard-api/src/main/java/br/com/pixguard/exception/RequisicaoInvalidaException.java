package br.com.pixguard.exception;

/** Parâmetro malformado fora do corpo JSON, ex.: identificador de caminho inválido (HTTP 400). */
public class RequisicaoInvalidaException extends RuntimeException {

    public RequisicaoInvalidaException(String mensagem) {
        super(mensagem);
    }
}
