package br.com.pixguard.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/** Payload do webhook no formato do BCB: {"pix":[{"endToEndId","txid","valor","horario"}]}. */
public record WebhookPixRequest(
        @NotEmpty(message = "pix é obrigatório e não pode ser vazio")
        @Valid
        List<@NotNull(message = "item de pix não pode ser nulo") PixItemRequest> pix) {
}
