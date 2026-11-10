package br.com.pixguard.service;

import br.com.pixguard.domain.Devolucao;

/** @param criada true se a devolução foi criada agora; false se era repetição de um pedido já registrado */
public record ResultadoRegistro(Devolucao devolucao, boolean criada) {
}
