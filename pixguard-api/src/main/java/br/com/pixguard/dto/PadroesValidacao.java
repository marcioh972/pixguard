package br.com.pixguard.dto;

/** Padrões compartilhados pelos DTOs da API. */
public final class PadroesValidacao {

    /**
     * Dinheiro no JSON: string decimal com exatamente 2 casas, maior que zero, até 13 dígitos inteiros
     * (cabe em numeric(15,2)). Ex.: "110.00". Rejeita "110", "110.5", "0.00", "-1.00", "1e2", "110,00".
     */
    public static final String VALOR_DECIMAL = "^(?!0\\.00$)(0|[1-9]\\d{0,12})\\.\\d{2}$";

    /** txid no formato do BCB: 26 a 35 caracteres alfanuméricos. */
    public static final String TXID = "^[a-zA-Z0-9]{26,35}$";

    /** endToEndId no formato do BCB: 32 caracteres alfanuméricos. */
    public static final String END_TO_END_ID = "^[a-zA-Z0-9]{32}$";

    /** idDevolucao no formato do BCB: 1 a 35 caracteres alfanuméricos. */
    public static final String ID_DEVOLUCAO = "^[a-zA-Z0-9]{1,35}$";

    /** Tamanho máximo do motivo de uma devolução. */
    public static final int TAMANHO_MAXIMO_MOTIVO = 140;

    private PadroesValidacao() {
    }
}
