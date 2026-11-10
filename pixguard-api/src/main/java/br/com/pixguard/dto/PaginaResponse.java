package br.com.pixguard.dto;

import java.util.List;
import java.util.function.Function;
import org.springframework.data.domain.Page;

/** Página no formato da API. Não expomos o Page do Spring: o JSON dele não é um contrato estável. */
public record PaginaResponse<T>(List<T> conteudo, int pagina, int tamanho, long totalElementos, int totalPaginas) {

    public static <E, T> PaginaResponse<T> de(Page<E> pagina, Function<E, T> conversor) {
        return new PaginaResponse<>(
                pagina.getContent().stream().map(conversor).toList(),
                pagina.getNumber(),
                pagina.getSize(),
                pagina.getTotalElements(),
                pagina.getTotalPages());
    }
}
