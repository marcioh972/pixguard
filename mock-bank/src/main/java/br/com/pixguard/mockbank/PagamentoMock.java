package br.com.pixguard.mockbank;

import java.time.Instant;

/** Pagamento como o banco o registra. {@code horario} é o horário do pagamento. */
public record PagamentoMock(String txid, String endToEndId, String valor, Instant horario) {
}
