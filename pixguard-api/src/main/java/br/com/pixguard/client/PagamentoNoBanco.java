package br.com.pixguard.client;

import java.math.BigDecimal;
import java.time.Instant;

/** Pagamento como o banco o enxerga. {@code horario} é o horário do pagamento, informado pelo banco. */
public record PagamentoNoBanco(String endToEndId, BigDecimal valor, Instant horario) {
}
