-- V2: tabela pagamento (um registro por endToEndId recebido do banco).
-- A regra central ("nunca baixar duas vezes, nunca esquecer pendente") é garantida aqui,
-- no banco; o código Java só trata os desfechos.

CREATE TABLE pagamento (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    end_to_end_id   VARCHAR(32)   NOT NULL,
    -- SEM FK de propósito: o webhook pode chegar antes da cobrança existir.
    txid            VARCHAR(35)   NOT NULL,
    cobranca_id     BIGINT,
    valor           NUMERIC(15,2) NOT NULL,
    valor_devolvido NUMERIC(15,2) NOT NULL DEFAULT 0,
    status          VARCHAR(30)   NOT NULL,
    -- pago_em: horário informado pelo banco (decide a validade). recebido_em: quando NÓS processamos.
    pago_em         TIMESTAMPTZ   NOT NULL,
    recebido_em     TIMESTAMPTZ   NOT NULL,
    version         BIGINT        NOT NULL DEFAULT 0,

    -- Idempotência: o mesmo endToEndId nunca entra duas vezes. Este UNIQUE é o alvo do
    -- INSERT ... ON CONFLICT (end_to_end_id) DO NOTHING do webhook, e o índice que ele cria
    -- serve também para localizar o pagamento por endToEndId (devolução, etapa 5).
    CONSTRAINT uk_pagamento_end_to_end_id UNIQUE (end_to_end_id),
    CONSTRAINT ck_pagamento_end_to_end_id_tamanho CHECK (char_length(end_to_end_id) = 32),
    CONSTRAINT ck_pagamento_txid_formato CHECK (txid ~ '^[a-zA-Z0-9]{26,35}$'),
    CONSTRAINT fk_pagamento_cobranca FOREIGN KEY (cobranca_id) REFERENCES cobranca (id),
    CONSTRAINT ck_pagamento_valor_positivo CHECK (valor > 0),
    -- Última linha de defesa das devoluções: nunca devolver mais do que foi pago.
    CONSTRAINT ck_pagamento_valor_devolvido CHECK (valor_devolvido >= 0 AND valor_devolvido <= valor),
    CONSTRAINT ck_pagamento_status CHECK (status IN
        ('AGUARDANDO_COBRANCA', 'CONCILIADO', 'RECEBIDO_APOS_EXPIRACAO', 'EXCEDENTE')),
    -- Só está "aguardando cobrança" quem ainda não tem cobrança vinculada, e vice-versa.
    -- Impede um pagamento esquecido como pendente apesar de já ter cobrança.
    CONSTRAINT ck_pagamento_status_cobranca CHECK ((status = 'AGUARDANDO_COBRANCA') = (cobranca_id IS NULL))
);

-- Atende (1) a conciliação ao criar uma cobrança ("há pagamento esperando este txid?") e
-- (2) a rede de segurança do job (etapa 4), que varre pagamentos ainda sem cobrança.
-- Parcial porque, em regime, quase todo pagamento tem cobrança: o índice fica minúsculo.
CREATE INDEX idx_pagamento_aguardando_txid ON pagamento (txid) WHERE cobranca_id IS NULL;

-- Atende "pagamentos de uma cobrança" (devoluções, consultas) e a checagem da FK ao
-- apagar/alterar cobranças, que sem este índice varreria a tabela inteira.
CREATE INDEX idx_pagamento_cobranca_id ON pagamento (cobranca_id);

-- GARANTIA DO BANCO contra baixa dupla: no máximo UM pagamento CONCILIADO por cobrança.
-- Mesmo que duas transações concorrentes decidam "esta cobrança é minha", a segunda falha aqui.
CREATE UNIQUE INDEX uk_pagamento_cobranca_conciliado ON pagamento (cobranca_id) WHERE status = 'CONCILIADO';
