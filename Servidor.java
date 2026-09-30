import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
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
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

public class Servidor {

    // Datos de conexión a MySQL (cambiar la clave por la tuya)
    static final String URL = "jdbc:mysql://localhost:3306/panalera_db";
    static final String USUARIO_BD = "root";
    static final String CLAVE_BD = "";

    // Cuenta de Gmail que ENVÍA los avisos (la de la pañalera)
    static final String CORREO_ORIGEN = "tu_correo@gmail.com";
    // Contraseña de aplicación de Google (16 letras), NO la contraseña normal del Gmail
    static final String CLAVE_CORREO = "tu_clave_de_aplicacion";

    public static void main(String[] args) throws IOException {
        HttpServer servidor = HttpServer.create(new InetSocketAddress(8080), 0);

        servidor.createContext("/", Servidor::servirArchivo);     // páginas de la carpeta web
        servidor.createContext("/login", Servidor::iniciarSesion); // recibe el mail
        servidor.createContext("/registro", Servidor::registrar);  // crea un usuario nuevo

        servidor.start();
        System.out.println("Servidor v3 iniciado. Abrí http://localhost:8080 en el navegador");
    }

    // Recibe el mail, lo busca en MySQL y responde con el rol
    static void iniciarSesion(HttpExchange ex) throws IOException {
        if (!"POST".equals(ex.getRequestMethod())) {
            servirArchivo(ex); // un GET a /registro.html es una página, no el formulario
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
            servirArchivo(ex); // un GET a /registro.html es una página, no el formulario
            return;
        }

        Map<String, String> datos = leerFormulario(ex);
        String nombre = datos.getOrDefault("nombre", "").trim();
        String email = datos.getOrDefault("email", "").trim().toLowerCase();
        String rol = datos.getOrDefault("rol", "");

        try {
            crearUsuario(nombre, email, rol);

            String respuesta = "Usuario creado correctamente.";
            if (correosActivados()) {
                // Se envía en un hilo aparte para no hacer esperar al usuario
                new Thread(() -> {
                    try {
                        enviarBienvenida(email, nombre, rol);
                    } catch (IOException e) {
                        System.out.println("No se pudo enviar el correo: " + e.getMessage());
                    }
                }).start();
                respuesta += " Te enviamos un aviso a tu mail.";
            }
            enviar(ex, 201, "text/plain; charset=UTF-8", respuesta);

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
        if (!email.matches("^[^\\s@<>]+@[^\\s@<>]+\\.[^\\s@<>]+$")) {
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

    // ---------- Envío de correo (SMTP de Gmail, sin librerías externas) ----------

    static boolean correosActivados() {
        return !CORREO_ORIGEN.equals("tu_correo@gmail.com")
            && !CLAVE_CORREO.equals("tu_clave_de_aplicacion");
    }

    static void enviarBienvenida(String destino, String nombre, String rol) throws IOException {
        String tipo = rol.equals("ADMINISTRADOR") ? "administrador" : "cliente";
        String texto = "Hola " + nombre + ",\r\n\r\n"
            + "Se creó una cuenta de " + tipo + " en Pañalera y Perfumería con este mail ("
            + destino + ").\r\n\r\n"
            + "Si no fuiste vos, ignorá este mensaje.\r\n";
        enviarCorreo(destino, "Tu cuenta en Pañalera y Perfumería", texto);
    }

    static void enviarCorreo(String destino, String asunto, String texto) throws IOException {
        SSLSocketFactory fabrica = (SSLSocketFactory) SSLSocketFactory.getDefault();

        try (SSLSocket socket = (SSLSocket) fabrica.createSocket("smtp.gmail.com", 465)) {
            socket.setSoTimeout(15000);
            BufferedReader in = new BufferedReader(
                new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            BufferedWriter out = new BufferedWriter(
                new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));

            leerRespuesta(in, "220");
            comando(out, in, "EHLO localhost", "250");
            comando(out, in, "AUTH LOGIN", "334");
            comando(out, in, base64(CORREO_ORIGEN), "334");
            comando(out, in, base64(CLAVE_CORREO.replace(" ", "")), "235");
            comando(out, in, "MAIL FROM:<" + CORREO_ORIGEN + ">", "250");
            comando(out, in, "RCPT TO:<" + destino + ">", "250");
            comando(out, in, "DATA", "354");

            String mensaje = "From: " + codificar("Pañalera y Perfumería") + " <" + CORREO_ORIGEN + ">\r\n"
                + "To: <" + destino + ">\r\n"
                + "Subject: " + codificar(asunto) + "\r\n"
                + "MIME-Version: 1.0\r\n"
                + "Content-Type: text/plain; charset=UTF-8\r\n"
                + "Content-Transfer-Encoding: base64\r\n\r\n"
                + Base64.getMimeEncoder().encodeToString(texto.getBytes(StandardCharsets.UTF_8));

            comando(out, in, mensaje + "\r\n.", "250"); // el punto solo en una línea cierra el mensaje
            out.write("QUIT\r\n");
            out.flush();
        }
    }

    // Manda una línea al servidor de correo y verifica el código de respuesta
    static void comando(BufferedWriter out, BufferedReader in, String texto, String esperado)
            throws IOException {
        out.write(texto + "\r\n");
        out.flush();
        leerRespuesta(in, esperado);
    }

    static void leerRespuesta(BufferedReader in, String esperado) throws IOException {
        String linea;
        do {
            linea = in.readLine();
            if (linea == null) {
                throw new IOException("El servidor de correo cortó la conexión.");
            }
        } while (linea.length() > 3 && linea.charAt(3) == '-'); // "250-..." = siguen más líneas

        if (!linea.startsWith(esperado)) {
            throw new IOException("Respuesta inesperada del servidor de correo: " + linea);
        }
    }

    static String base64(String texto) {
        return Base64.getEncoder().encodeToString(texto.getBytes(StandardCharsets.UTF_8));
    }

    // Permite tildes y ñ en el asunto y el nombre del remitente
    static String codificar(String texto) {
        return "=?UTF-8?B?" + base64(texto) + "?=";
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