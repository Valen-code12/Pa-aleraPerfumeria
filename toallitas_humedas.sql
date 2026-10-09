-- Toallitas húmedas (precios = columna "PRE. VENTA" del Excel de octubre 2026, stock = columna "FISICO")
-- Se puede ejecutar más de una vez: si el código ya existe, actualiza precio y stock.
USE panalera_db;

INSERT INTO productos (nombre, codigo, categoria, precio, stock) VALUES
  ('Toallitas húmedas Toddler Deluxe x48 (sin perfume)',          'O324', 'Toallitas húmedas', 2909.20, 52),
  ('Toallitas húmedas Toddler Deluxe x48 con aloe vera',          'O317', 'Toallitas húmedas', 2909.20, 33),
  ('Toallitas húmedas Huggies Triple Protección x96',             '1047', 'Toallitas húmedas', 9528.40,  3),
  ('Toallitas húmedas Huggies Limpieza Diaria x112',              '2189', 'Toallitas húmedas', 9716.00,  5)
ON DUPLICATE KEY UPDATE
  nombre = VALUES(nombre), categoria = VALUES(categoria),
  precio = VALUES(precio), stock = VALUES(stock);