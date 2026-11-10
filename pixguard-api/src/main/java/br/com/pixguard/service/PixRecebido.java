package br.com.pixguard.service;

import java.math.BigDecimal;
import java.time.Instant;

/** Pagamento informado pelo banco (webhook ou consulta do job). {@code pagoEm} é o horário do banco. */
public record PixRecebido(String endToEndId, String txid, BigDecimal valor, Instant pagoEm) {
}
