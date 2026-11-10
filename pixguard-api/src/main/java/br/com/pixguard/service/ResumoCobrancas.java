package br.com.pixguard.service;

public record ResumoCobrancas(int baixadas, int expiradas, int ignoradas, int falhas) {

    public boolean houveAtividade() {
        return baixadas + expiradas + ignoradas + falhas > 0;
    }
}
