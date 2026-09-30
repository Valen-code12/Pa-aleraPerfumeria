import javax.swing.JFrame;
import javax.swing.JLabel;
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

public class Servidor {
    
    // Datos de conexión a MySQL (cambiar la clave por la tuya)
    static final String URL = "jdbc:mysql://localhost:3306/panalera_db";
    static final String USUARIO_BD = "root";
    static final String CLAVE_BD = "";
    
    public static void main(String[] args) throws IOException {
       // Crear la ventana Swing visual
       JFrame ventana = new JFrame("Pañalera/Perfumeria");
       ventana.setSize(400, 300);
       ventana.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
       JLabel etiqueta = new JLabel("Inicio de sesión", JLabel.CENTER);
       ventana.add(etiqueta);
       ventana.setVisible(true);

       // Configuración correcta del Servidor Web
       HttpServer servidor = HttpServer.create(new InetSocketAddress(8080), 0);

       // Rutas
       servidor.createContext("/login", Servidor::iniciarSesion);
       servidor.createContext("/", Servidor::servirArchivo); // <-- ESTA LÍNEA ES CLAVE PARA EL HTML

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
