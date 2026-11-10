package br.com.pixguard.service;

public record ResumoPagamentos(int reprocessados, int ignorados, int falhas) {

    public boolean houveAtividade() {
        return reprocessados + ignorados + falhas > 0;
    }
}
