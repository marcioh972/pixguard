package br.com.pixguard.service;

import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Decide se uma falha significa "outra transação ganhou a disputa; repita e você verá o estado novo".
 * Só esses casos são repetidos: qualquer outro erro (dado inválido, bug, banco fora do ar) não é
 * mascarado por retry.
 */
final class ConflitoDeConcorrencia {

    /** Índice único parcial: no máximo um pagamento CONCILIADO por cobrança (migração V2). */
    static final String INDICE_BAIXA_UNICA = "uk_pagamento_cobranca_conciliado";

    private static final int PROFUNDIDADE_MAXIMA = 10;

    private ConflitoDeConcorrencia() {
    }

    static boolean ehRetentavel(Throwable erro) {
        Throwable atual = erro;
        for (int nivel = 0; atual != null && nivel < PROFUNDIDADE_MAXIMA; nivel++) {
            // Conflito de @Version, deadlock, falha de serialização: todos filhos de ConcurrencyFailureException.
            if (atual instanceof ConcurrencyFailureException || violaBaixaUnica(atual)) {
                return true;
            }
            atual = atual.getCause();
        }
        return false;
    }

    /**
     * Duas transações tentaram baixar a mesma cobrança com pagamentos diferentes: o banco recusou a segunda
     * pelo índice único. Repetida, ela passa a enxergar a cobrança já paga e vira EXCEDENTE.
     */
    private static boolean violaBaixaUnica(Throwable erro) {
        if (erro instanceof ConstraintViolationException violacao
                && INDICE_BAIXA_UNICA.equals(violacao.getConstraintName())) {
            return true;
        }
        return erro instanceof DataIntegrityViolationException
                && erro.getMessage() != null
                && erro.getMessage().contains(INDICE_BAIXA_UNICA);
    }
}
