package br.com.pixguard.support;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** Executa tarefas em threads LIBERADAS JUNTAS por uma CyclicBarrier, para maximizar a chance de colisão. */
public final class Paralelo {

    private static final long ESPERA_LARGADA_SEGUNDOS = 10;
    private static final long ESPERA_RESULTADO_SEGUNDOS = 60;

    private Paralelo() {
    }

    /** Uma thread por tarefa; devolve os resultados na ordem das tarefas. Falha se alguma lançar exceção. */
    public static <T> List<T> executarJuntas(List<Callable<T>> tarefas) throws Exception {
        int quantidade = tarefas.size();
        ExecutorService pool = Executors.newFixedThreadPool(quantidade);
        CyclicBarrier largada = new CyclicBarrier(quantidade);
        try {
            List<Future<T>> futuros = new ArrayList<>();
            for (Callable<T> tarefa : tarefas) {
                futuros.add(pool.submit(() -> {
                    largada.await(ESPERA_LARGADA_SEGUNDOS, TimeUnit.SECONDS);
                    return tarefa.call();
                }));
            }
            List<T> resultados = new ArrayList<>();
            for (Future<T> futuro : futuros) {
                resultados.add(futuro.get(ESPERA_RESULTADO_SEGUNDOS, TimeUnit.SECONDS));
            }
            return resultados;
        } finally {
            pool.shutdownNow();
        }
    }

    public static long quantos(List<Integer> statuses, int status) {
        return statuses.stream().filter(s -> s == status).count();
    }
}
