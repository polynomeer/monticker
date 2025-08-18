-- AAPL 1분 단위 시세 예시
INSERT INTO price_history
    (ticker_code, price, volume, "timestamp", exchange, currency, source)
VALUES ('AAPL', 19400, 100000, '2024-01-01T09:00:00Z', 'NASDAQ', 'USD', 'yahoo'),
       ('AAPL', 19420, 120000, '2024-01-01T09:01:00Z', 'NASDAQ', 'USD', 'yahoo'),
       ('AAPL', 19450, 150000, '2024-01-01T09:02:00Z', 'NASDAQ', 'USD', 'yahoo'),
       ('AAPL', 19380, 130000, '2024-01-01T09:03:00Z', 'NASDAQ', 'USD', 'yahoo'),
       ('AAPL', 19410, 160000, '2024-01-01T09:04:00Z', 'NASDAQ', 'USD',
        'yahoo')
ON CONFLICT (ticker_code, "timestamp") DO NOTHING;
