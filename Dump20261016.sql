CREATE DATABASE  IF NOT EXISTS `panalera_db` /*!40100 DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci */ /*!80016 DEFAULT ENCRYPTION='N' */;
USE `panalera_db`;
-- MySQL dump 10.13  Distrib 8.0.46, for Win64 (x86_64)
--
-- Host: localhost    Database: panalera_db
-- ------------------------------------------------------
-- Server version	8.0.46

/*!40101 SET @OLD_CHARACTER_SET_CLIENT=@@CHARACTER_SET_CLIENT */;
/*!40101 SET @OLD_CHARACTER_SET_RESULTS=@@CHARACTER_SET_RESULTS */;
/*!40101 SET @OLD_COLLATION_CONNECTION=@@COLLATION_CONNECTION */;
/*!50503 SET NAMES utf8 */;
/*!40103 SET @OLD_TIME_ZONE=@@TIME_ZONE */;
/*!40103 SET TIME_ZONE='+00:00' */;
/*!40014 SET @OLD_UNIQUE_CHECKS=@@UNIQUE_CHECKS, UNIQUE_CHECKS=0 */;
/*!40014 SET @OLD_FOREIGN_KEY_CHECKS=@@FOREIGN_KEY_CHECKS, FOREIGN_KEY_CHECKS=0 */;
/*!40101 SET @OLD_SQL_MODE=@@SQL_MODE, SQL_MODE='NO_AUTO_VALUE_ON_ZERO' */;
/*!40111 SET @OLD_SQL_NOTES=@@SQL_NOTES, SQL_NOTES=0 */;

--
-- Table structure for table `consulta_mail`
--

DROP TABLE IF EXISTS `consulta_mail`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `consulta_mail` (
  `idConsulta_Mail` int NOT NULL AUTO_INCREMENT,
  `nombre` varchar(100) NOT NULL,
  `email` varchar(100) NOT NULL,
  `asunto` varchar(150) DEFAULT NULL,
  `mensaje` text NOT NULL,
  `fecha` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`idConsulta_Mail`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `consulta_mail`
--

LOCK TABLES `consulta_mail` WRITE;
/*!40000 ALTER TABLE `consulta_mail` DISABLE KEYS */;
/*!40000 ALTER TABLE `consulta_mail` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `consultas_mail`
--

DROP TABLE IF EXISTS `consultas_mail`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `consultas_mail` (
  `idConsulta_Mail` int NOT NULL AUTO_INCREMENT,
  `nombre` varchar(100) NOT NULL,
  `email` varchar(100) NOT NULL,
  `asunto` varchar(150) DEFAULT NULL,
  `mensaje` text NOT NULL,
  `fecha` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`idConsulta_Mail`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `consultas_mail`
--

LOCK TABLES `consultas_mail` WRITE;
/*!40000 ALTER TABLE `consultas_mail` DISABLE KEYS */;
/*!40000 ALTER TABLE `consultas_mail` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `pedido_items`
--

DROP TABLE IF EXISTS `pedido_items`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `pedido_items` (
  `id_item` int NOT NULL AUTO_INCREMENT,
  `id_pedido` int NOT NULL,
  `id_producto` int DEFAULT NULL,
  `nombre` varchar(100) NOT NULL,
  `codigo` varchar(50) NOT NULL,
  `precio` decimal(10,2) NOT NULL,
  `cantidad` int NOT NULL,
  PRIMARY KEY (`id_item`),
  KEY `id_pedido` (`id_pedido`),
  KEY `id_producto` (`id_producto`),
  CONSTRAINT `pedido_items_ibfk_1` FOREIGN KEY (`id_pedido`) REFERENCES `pedidos` (`id_pedido`) ON DELETE CASCADE,
  CONSTRAINT `pedido_items_ibfk_2` FOREIGN KEY (`id_producto`) REFERENCES `productos` (`id_producto`) ON DELETE SET NULL
) ENGINE=InnoDB AUTO_INCREMENT=8 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `pedido_items`
--

LOCK TABLES `pedido_items` WRITE;
/*!40000 ALTER TABLE `pedido_items` DISABLE KEYS */;
INSERT INTO `pedido_items` VALUES (1,1,7,'Manteca de cacao Nivea Blackberry Shine','5522',6875.40,1),(2,1,8,'Jabón en barra Huggies Extra Suave','4398',2378.60,1),(3,2,8,'Jabón en barra Huggies Extra Suave','4398',2378.60,1),(4,3,8,'Jabón en barra Huggies Extra Suave','4398',2378.60,1),(5,4,3,'Toallitas húmedas Toddler Deluxe x48 con aloe vera','O317',2909.20,1),(6,4,8,'Jabón en barra Huggies Extra Suave','4398',2378.60,1),(7,4,11,'Pañales Duffy Cotton M x36','O362',10494.40,1);
/*!40000 ALTER TABLE `pedido_items` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `pedidos`
--

DROP TABLE IF EXISTS `pedidos`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `pedidos` (
  `id_pedido` int NOT NULL AUTO_INCREMENT,
  `fecha` datetime NOT NULL,
  `cliente_nombre` varchar(60) NOT NULL,
  `cliente_email` varchar(100) NOT NULL,
  `telefono` varchar(30) NOT NULL,
  `entrega` enum('RETIRO','ENVIO') NOT NULL,
  `direccion` varchar(150) DEFAULT NULL,
  `pago` enum('TRANSFERENCIA','EFECTIVO') NOT NULL,
  `costo_envio` decimal(10,2) NOT NULL DEFAULT '0.00',
  `total` decimal(10,2) NOT NULL,
  `observaciones` varchar(255) DEFAULT NULL,
  PRIMARY KEY (`id_pedido`)
) ENGINE=InnoDB AUTO_INCREMENT=5 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `pedidos`
--

LOCK TABLES `pedidos` WRITE;
/*!40000 ALTER TABLE `pedidos` DISABLE KEYS */;
INSERT INTO `pedidos` VALUES (1,'2026-10-09 16:15:38','Valentina Aleman','valentinaaleman07@gmail.com','1136584987','RETIRO',NULL,'EFECTIVO',0.00,9254.00,NULL),(2,'2026-10-10 20:05:01','Valentina Aleman','valentinaaleman07@gmail.com','113658498','RETIRO',NULL,'EFECTIVO',0.00,2378.60,NULL),(3,'2026-10-10 20:05:38','Valentina Aleman','valentinaaleman07@gmail.com','1136584987','RETIRO',NULL,'EFECTIVO',0.00,2378.60,NULL),(4,'2026-10-10 22:35:47','Valentina Aleman','valentinaaleman07@gmail.com','1136584987','RETIRO',NULL,'EFECTIVO',0.00,15782.20,NULL);
/*!40000 ALTER TABLE `pedidos` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `productos`
--

DROP TABLE IF EXISTS `productos`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `productos` (
  `id_producto` int NOT NULL AUTO_INCREMENT,
  `nombre` varchar(100) NOT NULL,
  `codigo` varchar(50) NOT NULL,
  `categoria` varchar(60) NOT NULL,
  `precio` decimal(10,2) NOT NULL,
  `stock` int NOT NULL DEFAULT '0',
  PRIMARY KEY (`id_producto`),
  UNIQUE KEY `codigo` (`codigo`)
) ENGINE=InnoDB AUTO_INCREMENT=12 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `productos`
--

LOCK TABLES `productos` WRITE;
/*!40000 ALTER TABLE `productos` DISABLE KEYS */;
INSERT INTO `productos` VALUES (2,'Toallitas húmedas Toddler Deluxe x48 (sin perfume)','O324','Toallitas húmedas',2909.20,52),(3,'Toallitas húmedas Toddler Deluxe x48 con aloe vera','O317','Toallitas húmedas',2909.20,32),(4,'Toallitas húmedas Huggies Triple Protección x96','1047','Toallitas húmedas',9528.40,3),(5,'Toallitas húmedas Huggies Limpieza Diaria x112','2189','Toallitas húmedas',9716.00,5),(6,'Manteca de cacao Nivea Cherry Shine','5478','Accesorios',6875.40,2),(7,'Manteca de cacao Nivea Blackberry Shine','5522','Accesorios',6875.40,4),(8,'Jabón en barra Huggies Extra Suave','4398','Accesorios',2378.60,0),(9,'Gasa estéril Suez 10x10 cm x100 trozos','9617','Accesorios',3794.00,0),(10,'Pañales Babysec Super Premium Recién Nacido (RN) x20','O924','Pañales',10967.40,4),(11,'Pañales Duffy Cotton M x36','O362','Pañales',10494.40,2);
/*!40000 ALTER TABLE `productos` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `proveedores`
--

DROP TABLE IF EXISTS `proveedores`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `proveedores` (
  `id_proveedor` int NOT NULL AUTO_INCREMENT,
  `nombre` varchar(100) NOT NULL,
  `contacto` varchar(100) NOT NULL,
  `telefono` varchar(30) NOT NULL,
  `direccion` varchar(150) NOT NULL,
  PRIMARY KEY (`id_proveedor`)
) ENGINE=InnoDB AUTO_INCREMENT=2 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `proveedores`
--

LOCK TABLES `proveedores` WRITE;
/*!40000 ALTER TABLE `proveedores` DISABLE KEYS */;
/*!40000 ALTER TABLE `proveedores` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `usuarios`
--

DROP TABLE IF EXISTS `usuarios`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `usuarios` (
  `id_usuario` int NOT NULL AUTO_INCREMENT,
  `nombre` varchar(60) NOT NULL,
  `email` varchar(100) NOT NULL,
  `rol` enum('ADMINISTRADOR','CLIENTE') NOT NULL,
  `clave` varchar(255) NOT NULL,
  PRIMARY KEY (`id_usuario`),
  UNIQUE KEY `email` (`email`)
) ENGINE=InnoDB AUTO_INCREMENT=6 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `usuarios`
--

LOCK TABLES `usuarios` WRITE;
/*!40000 ALTER TABLE `usuarios` DISABLE KEYS */;
INSERT INTO `usuarios` VALUES (3,'Morena Condori','moritacherry11@gmail.com','ADMINISTRADOR','fLSiMTSwdFcEJd8BDKnkCw==:6R4w4hifI6/kcabt2dpXJ2RPrQnYZ1oFT3pBjGlxW0s='),(4,'Valentina Lujan Aleman','valentinalujanaleman@gmail.com','ADMINISTRADOR','fGL/i1yAIWYxfPEzs4nqTQ==:YjPzJb+L9j0AaCKcr/HVz51bz3A4v/7j25mYd3YNPFE='),(5,'Valentina Aleman','valentinaaleman07@gmail.com','CLIENTE','sTOfzTJTA/IH0snHl4a5xw==:OFgGAu1I1k7yB3DHtlQjnz/Mt/qP9uWcS8EHGG0VisM=');
/*!40000 ALTER TABLE `usuarios` ENABLE KEYS */;
UNLOCK TABLES;
/*!40103 SET TIME_ZONE=@OLD_TIME_ZONE */;

/*!40101 SET SQL_MODE=@OLD_SQL_MODE */;
/*!40014 SET FOREIGN_KEY_CHECKS=@OLD_FOREIGN_KEY_CHECKS */;
/*!40014 SET UNIQUE_CHECKS=@OLD_UNIQUE_CHECKS */;
/*!40101 SET CHARACTER_SET_CLIENT=@OLD_CHARACTER_SET_CLIENT */;
/*!40101 SET CHARACTER_SET_RESULTS=@OLD_CHARACTER_SET_RESULTS */;
/*!40101 SET COLLATION_CONNECTION=@OLD_COLLATION_CONNECTION */;
/*!40111 SET SQL_NOTES=@OLD_SQL_NOTES */;

-- Dump completed on 2026-10-16 12:03:59
