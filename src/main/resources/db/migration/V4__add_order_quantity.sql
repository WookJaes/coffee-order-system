ALTER TABLE orders
    ADD COLUMN quantity INT NOT NULL DEFAULT 1 AFTER menu_id,
    ADD CONSTRAINT chk_orders_quantity_positive CHECK (quantity > 0);
