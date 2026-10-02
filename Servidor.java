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
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

public class Servidor {

    // Datos de conexión a MySQL (cambiar la clave por la tuya)
    static final String URL = "jdbc:mysql://localhost:3306/panalera_db";
    static final String USUARIO_BD = "root";
    static final String CLAVE_BD = "";

    // Cuenta de Gmail que ENVÍA los avisos (la de la pañalera)
    static final String CORREO_ORIGEN = "laplacitapm@gmail.com";
    // Contraseña de aplicación de Google (16 letras), NO la contraseña normal del Gmail
    static final String CLAVE_CORREO = "vfyv mjnf hvob nigu";

    // Reglas de los códigos de verificación
    static final long VIGENCIA_MS = 10 * 60 * 1000;      // el código vale 10 minutos
    static final long ESPERA_REENVIO_MS = 60 * 1000;     // hay que esperar 1 minuto para pedir otro
    static final int MAX_INTENTOS = 5;                   // intentos fallidos antes de anularlo

    public static void main(String[] args) throws IOException {
        HttpServer servidor = HttpServer.create(new InetSocketAddress(8080), 0);

        servidor.createContext("/", Servidor::servirArchivo);                     // páginas de la carpeta web
        servidor.createContext("/login", Servidor::iniciarSesion);                // paso 1: mail + contraseña
        servidor.createContext("/login/verificar", Servidor::verificarLogin);     // paso 2: código
        servidor.createContext("/registro", Servidor::registrar);                 // paso 1: datos del usuario
        servidor.createContext("/registro/verificar", Servidor::verificarRegistro); // paso 2: código
        servidor.createContext("/recuperar", Servidor::recuperar);                // paso 1: pide el código
        servidor.createContext("/recuperar/confirmar", Servidor::confirmarRecuperacion); // paso 2: código + clave nueva

        // Varios hilos: mientras se envía un mail no se bloquea a los demás usuarios
        servidor.setExecutor(Executors.newCachedThreadPool());

        servidor.start();
        System.out.println("Servidor v5 iniciado. Abrí http://localhost:8080 en el navegador");
        if (!correosActivados()) {
            System.out.println("AVISO: el correo no está configurado. Los códigos se van a mostrar acá en la consola.");
        }
    }

    // ---------- INICIO DE SESIÓN (2 pasos) ----------

    // Paso 1: comprueba mail y contraseña y, si están bien, envía un código al mail
    static void iniciarSesion(HttpExchange ex) throws IOException {
        if (!"POST".equals(ex.getRequestMethod())) {
            servirArchivo(ex); // un GET a /login.html es una página, no el formulario
            return;
        }

        Map<String, String> datos = leerFormulario(ex);
        String email = datos.getOrDefault("email", "").trim().toLowerCase();
        String clave = datos.getOrDefault("clave", "");

        try {
            Persona u = buscarUsuario(email, clave);
            emitirCodigo("LOGIN", email, u.getNombre(), null, null);
            enviar(ex, 200, "text/plain; charset=UTF-8", "Te enviamos un código a tu mail.");

        } catch (IllegalArgumentException e) {
            enviar(ex, 400, "text/plain; charset=UTF-8", e.getMessage());
        } catch (UsuarioNoEncontradoException e) {
            enviar(ex, 401, "text/plain; charset=UTF-8", e.getMessage());
        } catch (IllegalStateException e) {
            enviar(ex, 429, "text/plain; charset=UTF-8", e.getMessage());
        } catch (CorreoException e) {
            enviar(ex, 500, "text/plain; charset=UTF-8", e.getMessage());
        } catch (SQLException e) {
            e.printStackTrace();
            enviar(ex, 500, "text/plain; charset=UTF-8", "Error de conexión con la base de datos.");
        }
    }

    // Paso 2: comprueba el código y responde con el rol
    static void verificarLogin(HttpExchange ex) throws IOException {
        if (!"POST".equals(ex.getRequestMethod())) {
            servirArchivo(ex);
            return;
        }

        Map<String, String> datos = leerFormulario(ex);
        String email = datos.getOrDefault("email", "").trim().toLowerCase();
        String codigo = datos.getOrDefault("codigo", "").trim();

        try {
            CodigoPendiente c = validarCodigo("LOGIN", email, codigo);

            // Según el rol de la base se crea un Administrador o un Cliente
            Persona p = Persona.crear(c.rol(email), c.nombre, email);

            String json = "{\"rol\":\"" + escaparJson(p.getRol()) + "\",\"nombre\":\""
                          + escaparJson(p.getNombre()) + "\",\"pagina\":\""
                          + escaparJson(p.getPagina()) + "\"}";
            enviar(ex, 200, "application/json; charset=UTF-8", json);

        } catch (IllegalArgumentException e) {
            enviar(ex, 400, "text/plain; charset=UTF-8", e.getMessage());
        } catch (SQLException e) {
            e.printStackTrace();
            enviar(ex, 500, "text/plain; charset=UTF-8", "Error de conexión con la base de datos.");
        }
    }

    // ---------- REGISTRO (2 pasos) ----------

    // Paso 1: valida los datos y envía el código. Todavía NO se crea el usuario.
    static void registrar(HttpExchange ex) throws IOException {
        if (!"POST".equals(ex.getRequestMethod())) {
            servirArchivo(ex); // un GET a /registro.html es una página, no el formulario
            return;
        }

        Map<String, String> datos = leerFormulario(ex);
        String nombre = datos.getOrDefault("nombre", "").trim();
        String email = datos.getOrDefault("email", "").trim().toLowerCase();
        String rol = datos.getOrDefault("rol", "");
        String clave = datos.getOrDefault("clave", "");

        try {
            validarRegistro(nombre, email, rol, clave);

            if (existeEmail(email)) {
                enviar(ex, 409, "text/plain; charset=UTF-8", "Ya existe un usuario con ese mail.");
                return;
            }

            // Los datos quedan en memoria hasta que el usuario confirme el código
            emitirCodigo("REGISTRO", email, nombre, rol, generarHash(clave));
            enviar(ex, 200, "text/plain; charset=UTF-8", "Te enviamos un código a tu mail.");

        } catch (IllegalArgumentException e) {
            enviar(ex, 400, "text/plain; charset=UTF-8", e.getMessage());
        } catch (IllegalStateException e) {
            enviar(ex, 429, "text/plain; charset=UTF-8", e.getMessage());
        } catch (CorreoException e) {
            enviar(ex, 500, "text/plain; charset=UTF-8", e.getMessage());
        } catch (SQLException e) {
            e.printStackTrace();
            enviar(ex, 500, "text/plain; charset=UTF-8", "Error de conexión con la base de datos.");
        }
    }

    // Paso 2: si el código es correcto, guarda el usuario y envía el mail de bienvenida
    static void verificarRegistro(HttpExchange ex) throws IOException {
        if (!"POST".equals(ex.getRequestMethod())) {
            servirArchivo(ex);
            return;
        }

        Map<String, String> datos = leerFormulario(ex);
        String email = datos.getOrDefault("email", "").trim().toLowerCase();
        String codigo = datos.getOrDefault("codigo", "").trim();

        try {
            CodigoPendiente c = validarCodigo("REGISTRO", email, codigo);
            insertarUsuario(c.nombre, email, c.rol, c.claveHash);

            if (correosActivados()) {
                // Se envía en un hilo aparte para no hacer esperar al usuario
                new Thread(() -> {
                    try {
                        enviarBienvenida(email, c.nombre, c.rol);
                    } catch (IOException e) {
                        System.out.println("No se pudo enviar el correo: " + e.getMessage());
                    }
                }).start();
            }
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

    // ---------- RECUPERAR CONTRASEÑA (2 pasos) ----------

    // Paso 1: si el mail existe, envía un código. La respuesta es SIEMPRE la misma,
    // así nadie puede averiguar qué mails están registrados.
    static void recuperar(HttpExchange ex) throws IOException {
        if (!"POST".equals(ex.getRequestMethod())) {
            servirArchivo(ex);
            return;
        }

        Map<String, String> datos = leerFormulario(ex);
        String email = datos.getOrDefault("email", "").trim().toLowerCase();

        if (!email.matches("^[^\\s@<>]+@[^\\s@<>]+\\.[^\\s@<>]+$")) {
            enviar(ex, 400, "text/plain; charset=UTF-8", "Ingrese un mail válido.");
            return;
        }

        // Todo en un hilo aparte: así tarda lo mismo exista o no el mail
        new Thread(() -> {
            try {
                String nombre = buscarNombre(email);
                if (nombre != null) {
                    emitirCodigo("RECUPERACION", email, nombre, null, null);
                }
            } catch (Exception e) {
                System.out.println("Recuperación: " + e.getMessage());
            }
        }).start();

        enviar(ex, 200, "text/plain; charset=UTF-8",
               "Si el mail está registrado, te enviamos un código.");
    }

    // Paso 2: comprueba el código, cambia la contraseña y avisa por mail
    static void confirmarRecuperacion(HttpExchange ex) throws IOException {
        if (!"POST".equals(ex.getRequestMethod())) {
            servirArchivo(ex);
            return;
        }

        Map<String, String> datos = leerFormulario(ex);
        String email = datos.getOrDefault("email", "").trim().toLowerCase();
        String codigo = datos.getOrDefault("codigo", "").trim();
        String clave = datos.getOrDefault("clave", "");

        try {
            // Se revisa la contraseña ANTES de usar el código, para no gastar un intento por un error de tipeo
            if (clave.length() < 6) {
                throw new IllegalArgumentException("La contraseña debe tener al menos 6 caracteres.");
            }

            CodigoPendiente c = validarCodigo("RECUPERACION", email, codigo);
            actualizarClave(email, generarHash(clave));

            if (correosActivados()) {
                new Thread(() -> {
                    try {
                        enviarClaveCambiada(email, c.nombre);
                    } catch (IOException e) {
                        System.out.println("No se pudo enviar el correo: " + e.getMessage());
                    }
                }).start();
            }
            enviar(ex, 200, "text/plain; charset=UTF-8",
                   "Contraseña actualizada. Ya podés iniciar sesión.");

        } catch (IllegalArgumentException e) {
            enviar(ex, 400, "text/plain; charset=UTF-8", e.getMessage());
        } catch (SQLException e) {
            e.printStackTrace();
            enviar(ex, 500, "text/plain; charset=UTF-8", "Error de conexión con la base de datos.");
        }
    }

    // ---------- Códigos de verificación (se guardan en memoria) ----------

    static final SecureRandom ALEATORIO = new SecureRandom();

    // Clave del mapa: "PROPOSITO:mail" (ej. "LOGIN:ana@mail.com")
    static final Map<String, CodigoPendiente> codigos = new ConcurrentHashMap<>();

    // Genera un código de 6 dígitos, lo guarda y lo envía por mail
    static void emitirCodigo(String proposito, String email, String nombre, String rol, String claveHash)
            throws CorreoException {

        long ahora = System.currentTimeMillis();
        codigos.values().removeIf(c -> c.expira < ahora); // limpia los vencidos

        String llave = proposito + ":" + email;
        CodigoPendiente previo = codigos.get(llave);
        if (previo != null && ahora - previo.creado < ESPERA_REENVIO_MS) {
            throw new IllegalStateException("Esperá un minuto antes de pedir otro código.");
        }

        String codigo = String.format("%06d", ALEATORIO.nextInt(1_000_000));

        if (correosActivados()) {
            try {
                enviarCodigo(email, nombre, proposito, codigo);
            } catch (IOException e) {
                System.out.println("No se pudo enviar el correo: " + e.getMessage());
                throw new CorreoException("No se pudo enviar el correo. Intentá de nuevo en unos minutos.");
            }
        } else {
            // Modo prueba: sin Gmail configurado el código se muestra en la consola
            System.out.println("[MODO PRUEBA] Código " + proposito + " para " + email + ": " + codigo);
        }

        codigos.put(llave, new CodigoPendiente(codigo, ahora + VIGENCIA_MS, ahora, nombre, rol, claveHash));
    }

    // Comprueba el código ingresado. Si es correcto lo consume (sirve una sola vez).
    static synchronized CodigoPendiente validarCodigo(String proposito, String email, String ingresado) {
        String llave = proposito + ":" + email;
        CodigoPendiente c = codigos.get(llave);

        if (c == null) {
            throw new IllegalArgumentException("No hay un código pendiente. Pedí uno nuevo.");
        }
        if (System.currentTimeMillis() > c.expira) {
            codigos.remove(llave);
            throw new IllegalArgumentException("El código venció. Pedí uno nuevo.");
        }
        if (!MessageDigest.isEqual(c.codigo.getBytes(StandardCharsets.UTF_8),
                                   ingresado.getBytes(StandardCharsets.UTF_8))) {
            c.intentos++;
            if (c.intentos >= MAX_INTENTOS) {
                codigos.remove(llave);
                throw new IllegalArgumentException("Demasiados intentos. Pedí un código nuevo.");
            }
            throw new IllegalArgumentException("Código incorrecto. Te quedan "
                                               + (MAX_INTENTOS - c.intentos) + " intentos.");
        }

        codigos.remove(llave);
        return c;
    }

    // ---------- Base de datos ----------

    static void validarRegistro(String nombre, String email, String rol, String clave) {
        if (nombre.isEmpty()) {
            throw new IllegalArgumentException("Ingrese su nombre.");
        }
        if (!email.matches("^[^\\s@<>]+@[^\\s@<>]+\\.[^\\s@<>]+$")) {
            throw new IllegalArgumentException("Ingrese un mail válido.");
        }
        if (!rol.equals("ADMINISTRADOR") && !rol.equals("CLIENTE")) {
            throw new IllegalArgumentException("Rol inválido.");
        }
        if (clave.length() < 6) {
            throw new IllegalArgumentException("La contraseña debe tener al menos 6 caracteres.");
        }
    }

    static boolean existeEmail(String email) throws SQLException {
        return buscarNombre(email) != null;
    }

    // Devuelve el nombre del usuario con ese mail, o null si no existe
    static String buscarNombre(String email) throws SQLException {
        String sql = "SELECT nombre FROM usuarios WHERE email = ?";

        try (Connection con = DriverManager.getConnection(URL, USUARIO_BD, CLAVE_BD);
             PreparedStatement ps = con.prepareStatement(sql)) {

            ps.setString(1, email);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString("nombre") : null;
            }
        }
    }

    // INSERT del usuario nuevo (la clave ya llega hasheada)
    static void insertarUsuario(String nombre, String email, String rol, String claveHash) throws SQLException {
        String sql = "INSERT INTO usuarios (nombre, email, rol, clave) VALUES (?, ?, ?, ?)";

        try (Connection con = DriverManager.getConnection(URL, USUARIO_BD, CLAVE_BD);
             PreparedStatement ps = con.prepareStatement(sql)) {

            ps.setString(1, nombre);
            ps.setString(2, email);
            ps.setString(3, rol);
            ps.setString(4, claveHash); // nunca se guarda la contraseña en texto
            ps.executeUpdate();
        }
    }

    static void actualizarClave(String email, String claveHash) throws SQLException {
        String sql = "UPDATE usuarios SET clave = ? WHERE email = ?";

        try (Connection con = DriverManager.getConnection(URL, USUARIO_BD, CLAVE_BD);
             PreparedStatement ps = con.prepareStatement(sql)) {

            ps.setString(1, claveHash);
            ps.setString(2, email);
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

    static String escaparJson(String texto) {
        return texto.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    // ---------- Envío de correo (SMTP de Gmail, sin librerías externas) ----------

    static boolean correosActivados() {
        return !CORREO_ORIGEN.equals("tu_correo@gmail.com")
            && !CLAVE_CORREO.equals("tu_clave_de_aplicacion");
    }

    static void enviarBienvenida(String destino, String nombre, String rol) throws IOException {
        String tipo = Persona.crear(rol, nombre, destino).getTipo();
        String texto = "Hola " + nombre + ",\r\n\r\n"
            + "Se creó una cuenta de " + tipo + " en Pañalera y Perfumería con este mail ("
            + destino + ").\r\n\r\n"
            + "Si no fuiste vos, ignorá este mensaje.\r\n";
        enviarCorreo(destino, "Tu cuenta en Pañalera y Perfumería", texto);
    }

    // Aviso de que la contraseña se cambió (por si no fue el dueño de la cuenta)
    static void enviarClaveCambiada(String destino, String nombre) throws IOException {
        String texto = "Hola " + nombre + ",\r\n\r\n"
            + "La contraseña de tu cuenta en Pañalera y Perfumería se cambió correctamente.\r\n\r\n"
            + "Si no fuiste vos, pedí otro cambio de contraseña desde la página de inicio de sesión "
            + "y avisanos.\r\n";
        enviarCorreo(destino, "Tu contraseña fue cambiada", texto);
    }

    static void enviarCodigo(String destino, String nombre, String proposito, String codigo)
            throws IOException {
        String motivo;
        switch (proposito) {
            case "REGISTRO":     motivo = "terminar de crear tu cuenta"; break;
            case "RECUPERACION": motivo = "restablecer tu contraseña"; break;
            default:             motivo = "iniciar sesión"; break;
        }
        String texto = "Hola " + nombre + ",\r\n\r\n"
            + "Tu código para " + motivo + " en Pañalera y Perfumería es:\r\n\r\n"
            + "    " + codigo + "\r\n\r\n"
            + "Vence en " + (VIGENCIA_MS / 60000) + " minutos. No lo compartas con nadie.\r\n"
            + "Si no fuiste vos, ignorá este mensaje.\r\n";
        enviarCorreo(destino, "Tu código de verificación: " + codigo, texto);
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

    // Busca el usuario por mail y comprueba la contraseña
    static Persona buscarUsuario(String email, String clave)
            throws UsuarioNoEncontradoException, SQLException {

        if (email == null || email.isBlank() || !email.contains("@")) {
            throw new IllegalArgumentException("Ingrese un mail válido.");
        }
        if (clave == null || clave.isEmpty()) {
            throw new IllegalArgumentException("Ingrese su contraseña.");
        }

        String sql = "SELECT nombre, email, rol, clave FROM usuarios WHERE email = ?";

        try (Connection con = DriverManager.getConnection(URL, USUARIO_BD, CLAVE_BD);
             PreparedStatement ps = con.prepareStatement(sql)) {

            ps.setString(1, email.trim().toLowerCase());

            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next() && verificarClave(clave, rs.getString("clave"))) {
                    return Persona.crear(rs.getString("rol"),
                                         rs.getString("nombre"),
                                         rs.getString("email"));
                }
            }
        }
        // Mismo mensaje si falla el mail o la contraseña (no da pistas)
        throw new UsuarioNoEncontradoException("Mail o contraseña incorrectos.");
    }

    // ---------- Contraseñas: se guardan "hasheadas" (PBKDF2 + sal), nunca en texto ----------

    static final int ITERACIONES = 65536;

    // Devuelve "sal:hash" en Base64
    static String generarHash(String clave) {
        try {
            byte[] sal = new byte[16];
            ALEATORIO.nextBytes(sal);
            byte[] hash = pbkdf2(clave, sal);
            return Base64.getEncoder().encodeToString(sal) + ":"
                 + Base64.getEncoder().encodeToString(hash);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("No se pudo proteger la contraseña.", e);
        }
    }

    // Recalcula el hash con la misma sal y lo compara con el guardado
    static boolean verificarClave(String clave, String guardado) {
        if (guardado == null) {
            return false;
        }
        String[] partes = guardado.split(":");
        if (partes.length != 2) {
            return false;
        }
        try {
            byte[] sal = Base64.getDecoder().decode(partes[0]);
            byte[] esperado = Base64.getDecoder().decode(partes[1]);
            return MessageDigest.isEqual(esperado, pbkdf2(clave, sal));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            return false;
        }
    }

    static byte[] pbkdf2(String clave, byte[] sal) throws GeneralSecurityException {
        PBEKeySpec spec = new PBEKeySpec(clave.toCharArray(), sal, ITERACIONES, 256);
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                               .generateSecret(spec).getEncoded();
    }

    // Entrega los archivos HTML/CSS/JS de la carpeta "web"
    static void servirArchivo(HttpExchange ex) throws IOException {
        String ruta = ex.getRequestURI().getPath();
        if (ruta.equals("/")) {
            ruta = "/InicioDeSesion.html";
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
        else if (nombre.endsWith(".png")) tipo = "image/png";
        else if (nombre.endsWith(".jpg") || nombre.endsWith(".jpeg")) tipo = "image/jpeg";
        else if (nombre.endsWith(".svg")) tipo = "image/svg+xml";
        else if (nombre.endsWith(".ico")) tipo = "image/x-icon";

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

    // Un código esperando ser confirmado, junto con los datos que hay que recordar
    static class CodigoPendiente {
        final String codigo;
        final long expira;
        final long creado;
        final String nombre;
        final String rol;        // solo en REGISTRO
        final String claveHash;  // solo en REGISTRO
        int intentos = 0;

        CodigoPendiente(String codigo, long expira, long creado,
                        String nombre, String rol, String claveHash) {
            this.codigo = codigo;
            this.expira = expira;
            this.creado = creado;
            this.nombre = nombre;
            this.rol = rol;
            this.claveHash = claveHash;
        }

        // En el login el rol no se guarda en memoria: se consulta a la base al confirmar
        String rol(String email) throws SQLException {
            if (rol != null) {
                return rol;
            }
            String sql = "SELECT rol FROM usuarios WHERE email = ?";
            try (Connection con = DriverManager.getConnection(URL, USUARIO_BD, CLAVE_BD);
                 PreparedStatement ps = con.prepareStatement(sql)) {
                ps.setString(1, email);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return rs.getString("rol");
                    }
                }
            }
            throw new IllegalArgumentException("El usuario ya no existe.");
        }
    }

    static class UsuarioNoEncontradoException extends Exception {
        UsuarioNoEncontradoException(String mensaje) {
            super(mensaje);
        }
    }

    static class CorreoException extends Exception {
        CorreoException(String mensaje) {
            super(mensaje);
        }
    }
}