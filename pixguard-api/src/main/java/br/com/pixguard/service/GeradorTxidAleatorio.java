package br.com.pixguard.service;

import java.util.UUID;
import org.springframework.stereotype.Component;

/** UUID v4 sem hífens: 32 caracteres alfanuméricos, dentro do formato exigido (26 a 35). */
@Component
public class GeradorTxidAleatorio implements GeradorTxid {

    @Override
    public String gerar() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
