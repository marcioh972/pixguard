package br.com.pixguard.service;

/** Desfecho do job para uma cobrança sem retorno. */
public enum ResultadoCobranca {
    /** O banco confirmou o pagamento e a cobrança foi baixada pelo caminho único. */
    BAIXADA,
    /** Passou do prazo (mais tolerância) sem pagamento válido: marcada EXPIRADA. */
    EXPIRADA,
    /** Outro processo travou a cobrança, ou ela deixou de ser ATIVA/vencida desde a listagem. */
    IGNORADA
}
