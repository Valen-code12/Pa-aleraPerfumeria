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

CREATE TABLE IF NOT EXISTS pedidos (
    id_pedido      INT AUTO_INCREMENT PRIMARY KEY,
    fecha          DATETIME NOT NULL,
    cliente_nombre VARCHAR(60)  NOT NULL,
    cliente_email  VARCHAR(100) NOT NULL,
    telefono       VARCHAR(30)  NOT NULL,
    entrega        ENUM('RETIRO','ENVIO') NOT NULL,
    direccion      VARCHAR(150) NULL,                 -- solo si es envío
    pago           ENUM('TRANSFERENCIA','EFECTIVO') NOT NULL,
    costo_envio    DECIMAL(10,2) NOT NULL DEFAULT 0,
    total          DECIMAL(10,2) NOT NULL,
    observaciones  VARCHAR(255) NULL
);

CREATE TABLE IF NOT EXISTS pedido_items (
    id_item     INT AUTO_INCREMENT PRIMARY KEY,
    id_pedido   INT NOT NULL,
    id_producto INT NULL,                             -- queda vacío si después se borra el producto
    nombre      VARCHAR(100) NOT NULL,                -- se guarda el nombre y el precio del momento de la compra
    codigo      VARCHAR(50)  NOT NULL,
    precio      DECIMAL(10,2) NOT NULL,
    cantidad    INT NOT NULL,
    FOREIGN KEY (id_pedido)   REFERENCES pedidos(id_pedido)       ON DELETE CASCADE,
    FOREIGN KEY (id_producto) REFERENCES productos(id_producto)   ON DELETE SET NULL
);