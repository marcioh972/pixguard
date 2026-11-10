package br.com.pixguard.mockbank;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.springframework.stereotype.Service;

/** O "banco": registra pagamentos em memória, responde consultas e dispara webhooks. */
@Service
public class BancoSimulado {

    private static final String ALFABETO = "abcdefghijklmnopqrstuvwxyz0123456789";
    private static final int TAMANHO_SUFIXO_END_TO_END_ID = 11;
    private static final int COPIAS_PADRAO = 2;
    private static final long ATRASO_PADRAO_MS = 5_000;
    private static final DateTimeFormatter DATA_END_TO_END_ID =
            DateTimeFormatter.ofPattern("yyyyMMddHHmm").withZone(ZoneOffset.UTC);

    /** Por txid, o pagamento MAIS ANTIGO: é o que a consulta GET devolve. */
    private final Map<String, PagamentoMock> pagamentosPorTxid = new ConcurrentHashMap<>();

    private final DespachanteWebhook despachante;
    private final ScheduledExecutorService agendador;
    private final Clock clock;

    public BancoSimulado(DespachanteWebhook despachante, ScheduledExecutorService agendadorDeEntregas, Clock clock) {
        this.despachante = despachante;
        this.agendador = agendadorDeEntregas;
        this.clock = clock;
    }

    public ResultadoPagar pagar(PagarRequest pedido) {
        Instant base = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        int copias = pedido.copias() == null ? COPIAS_PADRAO : pedido.copias();

        // Ordem em que os pagamentos ACONTECERAM (do mais antigo ao mais recente).
        List<PagamentoMock> pagamentos = gerarPagamentos(pedido, base, copias);
        // O banco registra o pagamento já, independentemente de quando (ou se) o webhook sair.
        pagamentos.forEach(this::registrar);
        List<String> ids = pagamentos.stream().map(PagamentoMock::endToEndId).toList();

        List<PagamentoMock> envios = switch (pedido.modo()) {
            case NORMAL, ATRASADO -> pagamentos;
            case DUPLICADO -> Collections.nCopies(copias, pagamentos.get(0));
            case FORA_DE_ORDEM -> List.copyOf(pagamentos.reversed());
        };

        if (pedido.modo() == ModoPagamento.ATRASADO) {
            long atrasoMs = pedido.atrasoMs() == null ? ATRASO_PADRAO_MS : pedido.atrasoMs();
            envios.forEach(p -> agendador.schedule(() -> despachante.enviar(p), atrasoMs, TimeUnit.MILLISECONDS));
            return new ResultadoPagar(pedido.modo(), ids, List.of(), true);
        }

        List<ResultadoPagar.Entrega> entregas = envios.stream()
                .map(p -> new ResultadoPagar.Entrega(p.endToEndId(), despachante.enviar(p)))
                .toList();
        return new ResultadoPagar(pedido.modo(), ids, entregas, false);
    }

    public Optional<PagamentoMock> consultar(String txid) {
        return Optional.ofNullable(pagamentosPorTxid.get(txid));
    }

    public void limpar() {
        pagamentosPorTxid.clear();
    }

    private List<PagamentoMock> gerarPagamentos(PagarRequest pedido, Instant base, int copias) {
        return switch (pedido.modo()) {
            case NORMAL, ATRASADO, DUPLICADO -> List.of(novo(pedido, pedido.endToEndId(), base));
            // Pagamento k acontece k segundos depois do anterior; o 0 usa o endToEndId pedido, se houver.
            case FORA_DE_ORDEM -> IntStream.range(0, copias)
                    .mapToObj(k -> novo(pedido, k == 0 ? pedido.endToEndId() : null, base.plusSeconds(k)))
                    .toList();
        };
    }

    private PagamentoMock novo(PagarRequest pedido, String endToEndId, Instant horario) {
        String id = endToEndId != null ? endToEndId : gerarEndToEndId(horario);
        return new PagamentoMock(pedido.txid(), id, pedido.valor(), horario);
    }

    private void registrar(PagamentoMock candidato) {
        pagamentosPorTxid.merge(candidato.txid(), candidato,
                (atual, novo) -> novo.horario().isBefore(atual.horario()) ? novo : atual);
    }

    /** "E" + ISPB (8) + data/hora (12) + sufixo aleatório (11) = 32 caracteres. */
    private static String gerarEndToEndId(Instant horario) {
        StringBuilder sufixo = new StringBuilder(TAMANHO_SUFIXO_END_TO_END_ID);
        for (int i = 0; i < TAMANHO_SUFIXO_END_TO_END_ID; i++) {
            sufixo.append(ALFABETO.charAt(ThreadLocalRandom.current().nextInt(ALFABETO.length())));
        }
        return "E00000000" + DATA_END_TO_END_ID.format(horario) + sufixo;
    }
}
