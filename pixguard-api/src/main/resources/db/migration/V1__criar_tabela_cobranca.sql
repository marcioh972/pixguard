-- V1: tabela cobranca.
-- Regra de ouro: o banco é a última linha de defesa. Toda invariante que dá para
-- expressar como constraint está aqui, mesmo que o domínio Java também a valide.

CREATE TABLE cobranca (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    txid            VARCHAR(35)   NOT NULL,
    tipo            VARCHAR(20)   NOT NULL,
    valor           NUMERIC(15,2) NOT NULL,
    status          VARCHAR(20)   NOT NULL,
    data_vencimento DATE,
    criada_em       TIMESTAMPTZ   NOT NULL,
    expira_em       TIMESTAMPTZ   NOT NULL,
    atualizada_em   TIMESTAMPTZ   NOT NULL,
    version         BIGINT        NOT NULL DEFAULT 0,

    -- Identificador único da cobrança (formato do BCB: 26 a 35 caracteres alfanuméricos).
    -- O UNIQUE cria o índice usado por GET /cobrancas/{txid} e pela busca feita a cada
    -- webhook (pagamento -> cobrança). Sem ele, cada webhook seria um full scan.
    CONSTRAINT uk_cobranca_txid UNIQUE (txid),
    CONSTRAINT ck_cobranca_txid_formato CHECK (txid ~ '^[a-zA-Z0-9]{26,35}$'),

    CONSTRAINT ck_cobranca_tipo CHECK (tipo IN ('IMEDIATA', 'COM_VENCIMENTO')),
    CONSTRAINT ck_cobranca_valor_positivo CHECK (valor > 0),
    CONSTRAINT ck_cobranca_status CHECK (status IN
        ('ATIVA', 'PAGA', 'EXPIRADA', 'DEVOLVIDA_PARCIAL', 'DEVOLVIDA_TOTAL')),

    -- Vencimento é obrigatório se COM_VENCIMENTO e proibido se IMEDIATA.
    CONSTRAINT ck_cobranca_vencimento CHECK (
        (tipo = 'COM_VENCIMENTO' AND data_vencimento IS NOT NULL)
        OR (tipo = 'IMEDIATA' AND data_vencimento IS NULL)),

    CONSTRAINT ck_cobranca_expira_apos_criacao CHECK (expira_em > criada_em)
);

-- Atende o job (etapa 4): "cobranças ATIVAS cuja expira_em já passou", varridas em lote
-- com FOR UPDATE SKIP LOCKED. É parcial (só status ATIVA) porque a imensa maioria das
-- linhas, com o tempo, será PAGA/EXPIRADA/DEVOLVIDA e nunca entra nessa consulta:
-- o índice fica pequeno e barato de manter.
CREATE INDEX idx_cobranca_ativa_expira_em ON cobranca (expira_em) WHERE status = 'ATIVA';
