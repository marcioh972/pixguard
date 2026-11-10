-- V3: tabela devolucao (reembolso, total ou parcial, de um pagamento recebido).
-- O saldo devolvível NÃO é calculado aqui: vive em pagamento.valor_devolvido, alterado por UPDATE
-- atômico (ver DevolucaoService) e protegido pelo CHECK ck_pagamento_valor_devolvido da V2.

CREATE TABLE devolucao (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    id_devolucao VARCHAR(35)   NOT NULL,
    pagamento_id BIGINT        NOT NULL,
    valor        NUMERIC(15,2) NOT NULL,
    status       VARCHAR(20)   NOT NULL,
    motivo       VARCHAR(140),
    solicitada_em TIMESTAMPTZ  NOT NULL,
    version      BIGINT        NOT NULL DEFAULT 0,

    -- Idempotência da devolução: o idDevolucao é escolhido por quem chama e nunca se repete.
    -- Este UNIQUE é o alvo do INSERT ... ON CONFLICT (id_devolucao) DO NOTHING, e o índice que ele
    -- cria atende a busca por idDevolucao (repetição, consulta e registro de resultado).
    CONSTRAINT uk_devolucao_id_devolucao UNIQUE (id_devolucao),
    CONSTRAINT ck_devolucao_id_formato CHECK (id_devolucao ~ '^[a-zA-Z0-9]{1,35}$'),
    CONSTRAINT fk_devolucao_pagamento FOREIGN KEY (pagamento_id) REFERENCES pagamento (id),
    CONSTRAINT ck_devolucao_valor_positivo CHECK (valor > 0),
    CONSTRAINT ck_devolucao_status CHECK (status IN ('EM_PROCESSAMENTO', 'DEVOLVIDO', 'NAO_REALIZADO'))
);

-- Atende a listagem paginada "devoluções de um pagamento" e a checagem da FK ao mexer em
-- pagamento (sem este índice, cada checagem varreria a tabela devolucao inteira).
CREATE INDEX idx_devolucao_pagamento_id ON devolucao (pagamento_id);
