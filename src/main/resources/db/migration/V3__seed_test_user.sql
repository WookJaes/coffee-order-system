INSERT INTO users (id, name, created_at, updated_at)
VALUES (1, '테스트 사용자', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
AS seed
ON DUPLICATE KEY UPDATE
    name = seed.name,
    updated_at = CURRENT_TIMESTAMP;
