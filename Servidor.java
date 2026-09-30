import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.util.HashMap;
import java.util.Map;

public class Servidor {

    // Datos de conexión a MySQL (cambiar la clave por la tuya)
    static final String URL = "jdbc:mysql://localhost:3306/panalera_db";
    static final String USUARIO_BD = "root";
    static final String CLAVE_BD = "tu_clave";

    public static void main(String[] args) throws IOException {
        HttpServer servidor = HttpServer.create(new InetSocketAddress(8080), 0);

        servidor.createContext("/", Servidor::servirArchivo);     // páginas de la carpeta web
        servidor.createContext("/login", Servidor::iniciarSesion); // recibe el mail
        servidor.createContext("/registro", Servidor::registrar);  // crea un usuario nuevo

        servidor.start();
        System.out.println("Servidor iniciado. Abrí http://localhost:8080 en el navegador");
    }

    // Recibe el mail, lo busca en MySQL y responde con el rol
    static void iniciarSesion(HttpExchange ex) throws IOException {
        if (!"POST".equals(ex.getRequestMethod())) {
            enviar(ex, 405, "text/plain; charset=UTF-8", "Método no permitido");
            return;
        }

        String cuerpo = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String email = "";
        if (cuerpo.startsWith("email=")) {
            email = URLDecoder.decode(cuerpo.substring(6), StandardCharsets.UTF_8);
        }

        try {
            Usuario u = buscarUsuario(email);
            String json = "{\"rol\":\"" + u.rol + "\",\"nombre\":\""
                          + u.nombre.replace("\"", "\\\"") + "\"}";
            enviar(ex, 200, "application/json; charset=UTF-8", json);

        } catch (IllegalArgumentException e) {
            enviar(ex, 400, "text/plain; charset=UTF-8", e.getMessage());
        } catch (UsuarioNoEncontradoException e) {
            enviar(ex, 404, "text/plain; charset=UTF-8", e.getMessage());
        } catch (SQLException e) {
            e.printStackTrace();
            enviar(ex, 500, "text/plain; charset=UTF-8", "Error de conexión con la base de datos.");
        }
    }

    // Recibe nombre, mail y rol, y guarda el usuario nuevo en MySQL
    static void registrar(HttpExchange ex) throws IOException {
        if (!"POST".equals(ex.getRequestMethod())) {
            enviar(ex, 405, "text/plain; charset=UTF-8", "Método no permitido");
            return;
        }

        Map<String, String> datos = leerFormulario(ex);
        String nombre = datos.getOrDefault("nombre", "").trim();
        String email = datos.getOrDefault("email", "").trim().toLowerCase();
        String rol = datos.getOrDefault("rol", "");

        try {
            crearUsuario(nombre, email, rol);
            enviar(ex, 201, "text/plain; charset=UTF-8", "Usuario creado correctamente.");

        } catch (IllegalArgumentException e) {
            enviar(ex, 400, "text/plain; charset=UTF-8", e.getMessage());
        } catch (SQLIntegrityConstraintViolationException e) {
            // La columna email es UNIQUE: MySQL rechaza un mail repetido
            enviar(ex, 409, "text/plain; charset=UTF-8", "Ya existe un usuario con ese mail.");
        } catch (SQLException e) {
            e.printStackTrace();
            enviar(ex, 500, "text/plain; charset=UTF-8", "Error de conexión con la base de datos.");
        }
    }

    // INSERT del usuario nuevo (primero valida los datos)
    static void crearUsuario(String nombre, String email, String rol) throws SQLException {
        if (nombre.isEmpty()) {
            throw new IllegalArgumentException("Ingrese su nombre.");
        }
        if (email.isEmpty() || !email.contains("@")) {
            throw new IllegalArgumentException("Ingrese un mail válido.");
        }
        if (!rol.equals("ADMINISTRADOR") && !rol.equals("CLIENTE")) {
            throw new IllegalArgumentException("Rol inválido.");
        }

        String sql = "INSERT INTO usuarios (nombre, email, rol) VALUES (?, ?, ?)";

        try (Connection con = DriverManager.getConnection(URL, USUARIO_BD, CLAVE_BD);
             PreparedStatement ps = con.prepareStatement(sql)) {

            ps.setString(1, nombre);
            ps.setString(2, email);
            ps.setString(3, rol);
            ps.executeUpdate();
        }
    }

    // Convierte "nombre=Ana&email=a%40b.com&rol=CLIENTE" en un Map
    static Map<String, String> leerFormulario(HttpExchange ex) throws IOException {
        String cuerpo = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, String> datos = new HashMap<>();

        for (String par : cuerpo.split("&")) {
            String[] partes = par.split("=", 2);
            if (partes.length == 2) {
                datos.put(URLDecoder.decode(partes[0], StandardCharsets.UTF_8),
                          URLDecoder.decode(partes[1], StandardCharsets.UTF_8));
            }
        }
        return datos;
    }

    // Misma búsqueda que en la versión Swing
    static Usuario buscarUsuario(String email)
            throws UsuarioNoEncontradoException, SQLException {

        if (email == null || email.isBlank() || !email.contains("@")) {
            throw new IllegalArgumentException("Ingrese un mail válido.");
        }

        String sql = "SELECT nombre, email, rol FROM usuarios WHERE email = ?";

        try (Connection con = DriverManager.getConnection(URL, USUARIO_BD, CLAVE_BD);
             PreparedStatement ps = con.prepareStatement(sql)) {

            ps.setString(1, email.trim().toLowerCase());

            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return new Usuario(rs.getString("nombre"),
                                       rs.getString("email"),
                                       rs.getString("rol"));
                }
            }
        }
        throw new UsuarioNoEncontradoException("No existe un usuario con ese mail.");
    }

    // Entrega los archivos HTML/CSS/JS de la carpeta "web"
    static void servirArchivo(HttpExchange ex) throws IOException {
        String ruta = ex.getRequestURI().getPath();
        if (ruta.equals("/")) {
            ruta = "/login.html";
        }

        Path base = Paths.get("web").toAbsolutePath().normalize();
        Path archivo = base.resolve(ruta.substring(1)).normalize();

        // No permite salir de la carpeta web
        if (!archivo.startsWith(base) || !Files.isRegularFile(archivo)) {
            enviar(ex, 404, "text/plain; charset=UTF-8", "Página no encontrada");
            return;
        }

        String nombre = archivo.getFileName().toString();
        String tipo = "application/octet-stream";
        if (nombre.endsWith(".html")) tipo = "text/html; charset=UTF-8";
        else if (nombre.endsWith(".css")) tipo = "text/css; charset=UTF-8";
        else if (nombre.endsWith(".js")) tipo = "application/javascript; charset=UTF-8";

        enviar(ex, 200, tipo, Files.readAllBytes(archivo));
    }

    static void enviar(HttpExchange ex, int codigo, String tipo, String texto) throws IOException {
        enviar(ex, codigo, tipo, texto.getBytes(StandardCharsets.UTF_8));
    }

    static void enviar(HttpExchange ex, int codigo, String tipo, byte[] datos) throws IOException {
        ex.getResponseHeaders().set("Content-Type", tipo);
        ex.sendResponseHeaders(codigo, datos.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(datos);
        }
    }

    // Clases anidadas (así no chocan con IniciodeSesion.java si lo dejás en la carpeta)
    static class Usuario {
        String nombre;
        String email;
        String rol; // "ADMINISTRADOR" o "CLIENTE"

        Usuario(String nombre, String email, String rol) {
            this.nombre = nombre;
            this.email = email;
            this.rol = rol;
        }
    }

    static class UsuarioNoEncontradoException extends Exception {
        UsuarioNoEncontradoException(String mensaje) {
            super(mensaje);
        }
    }
}