package br.com.pixguard.mockbank;

import java.util.List;

/**
 * @param endToEndIds pagamentos registrados, do mais antigo ao mais recente
 * @param entregas    resultado de cada envio de webhook (vazio quando agendado)
 * @param agendado    true no modo ATRASADO: o webhook ainda vai sair
 */
public record ResultadoPagar(ModoPagamento modo, List<String> endToEndIds, List<Entrega> entregas, boolean agendado) {

    public record Entrega(String endToEndId, String resultado) {
    }
}
