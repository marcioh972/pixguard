package br.com.pixguard.service;

/** Desfecho do processamento de um pagamento recebido. */
public enum ResultadoProcessamento {
    CONCILIADO,
    AGUARDANDO_COBRANCA,
    RECEBIDO_APOS_EXPIRACAO,
    EXCEDENTE,
    DUPLICADO,
    /** Nada a fazer: outro processo travou o item, o estado mudou ou ainda não há cobrança. */
    IGNORADO
}
