package br.com.pixguard.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.regex.Pattern;

/** Validações compartilhadas pelas entidades. As mesmas regras existem como CHECK no banco. */
final class Validacoes {

    private static final Pattern FORMATO_TXID = Pattern.compile("^[a-zA-Z0-9]{26,35}$");
    private static final Pattern FORMATO_END_TO_END_ID = Pattern.compile("^[a-zA-Z0-9]{32}$");
    private static final Pattern FORMATO_ID_DEVOLUCAO = Pattern.compile("^[a-zA-Z0-9]{1,35}$");
    private static final int TAMANHO_MAXIMO_MOTIVO = 140;
    private static final int ESCALA_DINHEIRO = 2;
    private static final int PRECISAO_MAXIMA = 15;

    private Validacoes() {
    }

    static String txid(String txid) {
        obrigatorio(txid, "txid");
        if (!FORMATO_TXID.matcher(txid).matches()) {
            throw new IllegalArgumentException("txid deve ter de 26 a 35 caracteres alfanuméricos");
        }
        return txid;
    }

    static String endToEndId(String endToEndId) {
        obrigatorio(endToEndId, "endToEndId");
        if (!FORMATO_END_TO_END_ID.matcher(endToEndId).matches()) {
            throw new IllegalArgumentException("endToEndId deve ter 32 caracteres alfanuméricos");
        }
        return endToEndId;
    }

    static String idDevolucao(String idDevolucao) {
        obrigatorio(idDevolucao, "idDevolucao");
        if (!FORMATO_ID_DEVOLUCAO.matcher(idDevolucao).matches()) {
            throw new IllegalArgumentException("idDevolucao deve ter de 1 a 35 caracteres alfanuméricos");
        }
        return idDevolucao;
    }

    /** Motivo é opcional. */
    static String motivo(String motivo) {
        if (motivo != null && motivo.length() > TAMANHO_MAXIMO_MOTIVO) {
            throw new IllegalArgumentException("motivo deve ter no máximo 140 caracteres");
        }
        return motivo;
    }

    /** Escala 2 sem arredondar: valor com mais de 2 casas é rejeitado, nunca truncado em silêncio. */
    static BigDecimal valor(BigDecimal valor) {
        obrigatorio(valor, "valor");
        BigDecimal normalizado;
        try {
            normalizado = valor.setScale(ESCALA_DINHEIRO, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("valor deve ter no máximo 2 casas decimais: " + valor, e);
        }
        if (normalizado.signum() <= 0) {
            throw new IllegalArgumentException("valor deve ser maior que zero");
        }
        if (normalizado.precision() > PRECISAO_MAXIMA) {
            throw new IllegalArgumentException("valor excede a precisão máxima de 15 dígitos");
        }
        return normalizado;
    }

    static <T> T obrigatorio(T valor, String campo) {
        if (valor == null) {
            throw new IllegalArgumentException(campo + " é obrigatório");
        }
        return valor;
    }
}
