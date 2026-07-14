INSERT INTO menus (id, name, price, status, created_at, updated_at)
VALUES
    (1, '아메리카노', 4500, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (2, '에스프레소', 4000, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (3, '카페라떼', 5000, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (4, '카푸치노', 5000, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (5, '바닐라라떼', 5500, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
AS seed
ON DUPLICATE KEY UPDATE
    name = seed.name,
    price = seed.price,
    status = seed.status,
    updated_at = CURRENT_TIMESTAMP;
