CREATE TABLE users (
    id BIGINT NOT NULL AUTO_INCREMENT,
    name VARCHAR(100) NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE menus (
    id BIGINT NOT NULL AUTO_INCREMENT,
    name VARCHAR(100) NOT NULL,
    price INT NOT NULL,
    status VARCHAR(20) NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT chk_menus_price_positive CHECK (price > 0),
    CONSTRAINT chk_menus_status CHECK (status IN ('ACTIVE', 'SOLD_OUT'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE points (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    balance INT NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT uk_points_user_id UNIQUE (user_id),
    CONSTRAINT fk_points_user_id FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT chk_points_balance_non_negative CHECK (balance >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE orders (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    menu_id BIGINT NOT NULL,
    idempotency_key VARCHAR(100) NOT NULL,
    order_price INT NOT NULL,
    status VARCHAR(20) NOT NULL,
    ordered_at DATETIME NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT uk_orders_user_id_idempotency_key UNIQUE (user_id, idempotency_key),
    CONSTRAINT fk_orders_user_id FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_orders_menu_id FOREIGN KEY (menu_id) REFERENCES menus (id),
    CONSTRAINT chk_orders_order_price_positive CHECK (order_price > 0),
    CONSTRAINT chk_orders_status CHECK (status IN ('PAID', 'FAILED', 'CANCELED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE point_histories (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    order_id BIGINT NULL,
    type VARCHAR(20) NOT NULL,
    amount INT NOT NULL,
    balance_after INT NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT fk_point_histories_user_id FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_point_histories_order_id FOREIGN KEY (order_id) REFERENCES orders (id),
    CONSTRAINT chk_point_histories_type CHECK (type IN ('CHARGE', 'USE')),
    CONSTRAINT chk_point_histories_amount_positive CHECK (amount > 0),
    CONSTRAINT chk_point_histories_balance_after_non_negative CHECK (balance_after >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE order_events (
    id BIGINT NOT NULL AUTO_INCREMENT,
    order_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    menu_id BIGINT NOT NULL,
    payment_amount INT NOT NULL,
    status VARCHAR(20) NOT NULL,
    retry_count INT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT uk_order_events_order_id UNIQUE (order_id),
    CONSTRAINT fk_order_events_order_id FOREIGN KEY (order_id) REFERENCES orders (id),
    CONSTRAINT fk_order_events_user_id FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_order_events_menu_id FOREIGN KEY (menu_id) REFERENCES menus (id),
    CONSTRAINT chk_order_events_payment_amount_positive CHECK (payment_amount > 0),
    CONSTRAINT chk_order_events_status CHECK (status IN ('PENDING', 'SENT', 'FAILED')),
    CONSTRAINT chk_order_events_retry_count_non_negative CHECK (retry_count >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE INDEX idx_orders_status_ordered_at ON orders (status, ordered_at);
CREATE INDEX idx_order_events_status_created_at ON order_events (status, created_at);
