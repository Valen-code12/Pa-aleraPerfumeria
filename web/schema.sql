CREATE DATABASE IF NOT EXISTS panalera_db;
USE panalera_db;

CREATE TABLE IF NOT EXISTS usuarios (
    id_usuario INT AUTO_INCREMENT PRIMARY KEY,
    nombre VARCHAR(60) NOT NULL,
    email  VARCHAR(100) NOT NULL UNIQUE,
    rol    ENUM('ADMINISTRADOR','CLIENTE') NOT NULL
);

INSERT INTO usuarios (nombre, email, rol) VALUES
('Administrador', 'admin@gmail.com',   'ADMINISTRADOR'),
('Cliente',    'cliente@gmail.com', 'CLIENTE');