ALTER TABLE point_histories
    ADD CONSTRAINT chk_point_histories_type_order_id
        CHECK (
            (type = 'CHARGE' AND order_id IS NULL)
            OR (type = 'USE' AND order_id IS NOT NULL)
        ),
    ADD CONSTRAINT uk_point_histories_order_id UNIQUE (order_id);
