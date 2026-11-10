package br.com.pixguard.exception;

/** Lançada pelo domínio quando se tenta uma transição de estado que a regra de negócio não permite. */
public class TransicaoInvalidaException extends RuntimeException {

    public TransicaoInvalidaException(String entidade, String chave, Enum<?> de, Enum<?> para) {
        super("%s %s: transição inválida de %s para %s".formatted(entidade, chave, de, para));
    }
}
