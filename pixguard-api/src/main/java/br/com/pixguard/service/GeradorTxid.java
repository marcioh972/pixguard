package br.com.pixguard.service;

/** Fonte de txids. É uma interface para os testes poderem prever o txid (webhook antes da cobrança). */
public interface GeradorTxid {

    String gerar();
}
