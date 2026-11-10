package br.com.pixguard.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

/** Clock de teste cujo "agora" é definido pelo próprio teste. Thread-safe (usado nos testes de concorrência). */
public final class RelogioControlavel extends Clock {

    public static final Instant INICIO_PADRAO = Instant.parse("2026-11-05T12:00:00Z");

    private final AtomicReference<Instant> agora;

    public RelogioControlavel(Instant inicial) {
        this.agora = new AtomicReference<>(inicial);
    }

    public void definir(Instant instante) {
        agora.set(instante);
    }

    public void avancar(Duration duracao) {
        agora.updateAndGet(atual -> atual.plus(duracao));
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return Clock.fixed(instant(), zone);
    }

    @Override
    public Instant instant() {
        return agora.get();
    }
}
