package br.com.pixguard.mockbank;

/**
 * Como o banco entrega o webhook:
 * NORMAL: um webhook, agora.
 * DUPLICADO: o MESMO webhook (mesmo endToEndId) reenviado {@code copias} vezes.
 * ATRASADO: o banco já registrou o pagamento, mas o webhook só sai depois de {@code atrasoMs}
 *           (com um atraso enorme, equivale a um webhook perdido: o job descobre pela consulta).
 * FORA_DE_ORDEM: {@code copias} pagamentos distintos, entregues do mais recente para o mais antigo.
 */
public enum ModoPagamento {
    NORMAL,
    DUPLICADO,
    ATRASADO,
    FORA_DE_ORDEM
}
