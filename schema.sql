CREATE DATABASE IF NOT EXISTS panalera_db;
USE panalera_db;
 
CREATE TABLE IF NOT EXISTS usuarios (
    id_usuario INT AUTO_INCREMENT PRIMARY KEY,
    nombre VARCHAR(60) NOT NULL,
    email  VARCHAR(100) NOT NULL UNIQUE,
    rol    ENUM('ADMINISTRADOR','CLIENTE') NOT NULL,
    clave  VARCHAR(255) NOT NULL   -- contraseña hasheada (sal:hash), nunca en texto
);

CREATE TABLE IF NOT EXISTS productos (
    id_producto INT AUTO_INCREMENT PRIMARY KEY,
    nombre    VARCHAR(100) NOT NULL,
    codigo    VARCHAR(50)  NOT NULL UNIQUE,
    categoria VARCHAR(60)  NOT NULL,
    precio    DECIMAL(10,2) NOT NULL,
    stock     INT NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS proveedores (
    id_proveedor INT AUTO_INCREMENT PRIMARY KEY,
    nombre    VARCHAR(100) NOT NULL,
    contacto  VARCHAR(100) NOT NULL,
    telefono  VARCHAR(30)  NOT NULL,
    direccion VARCHAR(150) NOT NULL
);