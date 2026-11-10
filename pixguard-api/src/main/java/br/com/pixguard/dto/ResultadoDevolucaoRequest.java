package br.com.pixguard.dto;

import br.com.pixguard.domain.StatusDevolucao;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;

public record ResultadoDevolucaoRequest(@NotNull(message = "status é obrigatório") StatusDevolucao status) {

    @JsonIgnore
    @AssertTrue(message = "status deve ser DEVOLVIDO ou NAO_REALIZADO")
    public boolean isStatusFinal() {
        return status == null || status != StatusDevolucao.EM_PROCESSAMENTO;
    }
}
