import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipEntry;
import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
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
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.Locale;
import java.util.TreeMap;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.stream.Stream;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import com.sun.net.httpserver.HttpHandler;

public class Servidor {

    // Las contraseñas NO van en el código: se leen del archivo config.properties,
    // que está en .gitignore y por eso no se sube a GitHub.
    static final java.util.Properties CONFIG = cargarConfig();

    // Datos de conexión a MySQL
    static final String URL = CONFIG.getProperty("URL_BD", "jdbc:mysql://localhost:3306/panalera_db");
    static final String USUARIO_BD = CONFIG.getProperty("USUARIO_BD", "root");
    static final String CLAVE_BD = CONFIG.getProperty("CLAVE_BD", "");

    // Cuenta de Gmail que ENVÍA los avisos (la de la pañalera)
    static final String CORREO_ORIGEN = CONFIG.getProperty("CORREO_ORIGEN", "");
    // Contraseña de aplicación de Google (16 letras)
    static final String CLAVE_CORREO = CONFIG.getProperty("CLAVE_CORREO", "");

        static java.util.Properties cargarConfig() {
        java.util.Properties p = new java.util.Properties();
        try (java.io.Reader r = new java.io.InputStreamReader(
                new java.io.FileInputStream("config.properties"), java.nio.charset.StandardCharsets.UTF_8)) {
            p.load(r);
        } catch (java.io.IOException e) {
            System.out.println("[CONFIG] No se pudo leer config.properties (" + e.getMessage() + "). "
                    + "Copiá config.properties.example como config.properties y completalo.");
        }

        // Las variables de entorno (Railway) tienen prioridad
        for (String k : new String[]{"URL_BD", "USUARIO_BD", "CLAVE_BD", "CORREO_ORIGEN", "CLAVE_CORREO"}) {
            if (System.getenv(k) != null) p.setProperty(k, System.getenv(k));
        }

        return p;
    }

    // Mientras sea true, el código de verificación también se muestra en esta consola.
    // Ponelo en false cuando termines de probar.
    static final boolean DEPURACION = true;

    // Reglas de los códigos de verificación
    static final long VIGENCIA_MS = 10 * 60 * 1000;      // el código vale 10 minutos
    static final long ESPERA_REENVIO_MS = 60 * 1000;     // hay que esperar 1 minuto para pedir otro
    static final int MAX_INTENTOS = 5;                   // intentos fallidos antes de anularlo

    static final String ORIGEN_PERMITIDO = "https://valen-code12.github.io";

    // Registra una ruta agregándole CORS y respondiendo el "preflight" (OPTIONS)
    static void ruta(HttpServer servidor, String path, HttpHandler manejador) {
    servidor.createContext(path, ex -> {
        ex.getResponseHeaders().set("Access-Control-Allow-Origin", ORIGEN_PERMITIDO);
        ex.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
        ex.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type, X-Token, X-Nombre");
        if ("OPTIONS".equals(ex.getRequestMethod())) {
            ex.sendResponseHeaders(204, -1);
            ex.close();
            return;
        }
        manejador.handle(ex);
    });
    }

    public static void main(String[] args) throws IOException {
        int puerto = Integer.parseInt(
            System.getenv().getOrDefault("PORT", "8080")
        );

        HttpServer servidor = HttpServer.create(
            new InetSocketAddress("0.0.0.0", puerto), 0
        );  

        ruta(servidor, "/", Servidor::servirArchivo);                     // páginas de la carpeta web
        ruta(servidor, "/login", Servidor::iniciarSesion);                // paso 1: mail + contraseña
        ruta(servidor, "/login/verificar", Servidor::verificarLogin);     // paso 2: código
        ruta(servidor, "/registro", Servidor::registrar);                 // paso 1: datos del usuario
        ruta(servidor, "/registro/verificar", Servidor::verificarRegistro); // paso 2: código
        ruta(servidor, "/recuperar", Servidor::recuperar);                // paso 1: pide el código
        ruta(servidor, "/recuperar/confirmar", Servidor::confirmarRecuperacion); // paso 2: código + clave nueva
        ruta(servidor, "/api/productos", Servidor::productos);            // alta, baja, edición y lista (solo administrador)
        ruta(servidor, "/api/proveedores", Servidor::proveedores);        // alta, baja, edición y lista (solo administrador)
        ruta(servidor, "/api/documentos", Servidor::documentos);          // PDF o Excel de cada producto (solo administrador)
        ruta(servidor, "/api/pedidos", Servidor::pedidos);                // hacer un pedido (cliente con sesión)
        ruta(servidor, "/api/catalogo", Servidor::catalogo);              // lista de productos para cualquier usuario con sesión

        // Varios hilos: mientras se envía un mail no se bloquea a los demás usuarios
        servidor.setExecutor(Executors.newCachedThreadPool());

        servidor.start();
        System.out.println("Servidor v6 iniciado. Abrí http://localhost:8080 en el navegador");
        if (!correosActivados()) {
            System.out.println("AVISO: el correo no está configurado. Los códigos se van a mostrar acá en la consola.");
        }
    }

    // INICIO DE SESIÓN (2 pasos) 

    // Paso 1: comprueba mail y contraseña y, si están bien, envía un código al mail
    static void iniciarSesion(HttpExchange ex) throws IOException {
        if (!"POST".equals(ex.getRequestMethod())) {
            servirArchivo(ex); // un GET a /login.html es una página
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

            // El token identifica la sesión: las rutas del administrador lo piden en cada pedido
            String json = "{\"rol\":\"" + escaparJson(p.getRol()) + "\",\"nombre\":\""
                          + escaparJson(p.getNombre()) + "\",\"pagina\":\""
                          + escaparJson(p.getPagina()) + "\",\"token\":\""
                          + crearSesion(p.getRol(), p.getNombre(), p.getEmail()) + "\"}";
            enviar(ex, 200, "application/json; charset=UTF-8", json);

        } catch (IllegalArgumentException e) {
            enviar(ex, 400, "text/plain; charset=UTF-8", e.getMessage());
        } catch (SQLException e) {
            e.printStackTrace();
            enviar(ex, 500, "text/plain; charset=UTF-8", "Error de conexión con la base de datos.");
        }
    }

    // REGISTRO (2 pasos)

    // Paso 1: valida los datos y envía el código. Todavía NO se crea el usuario.
    static void registrar(HttpExchange ex) throws IOException {
        if (!"POST".equals(ex.getRequestMethod())) {
            servirArchivo(ex); // un GET a /registro.html es una página, no el formulario
            return;
        }

        Map<String, String> datos = leerFormulario(ex);
        String nombre = datos.getOrDefault("nombre", "").trim();
        String email = datos.getOrDefault("email", "").trim().toLowerCase();
        // Por seguridad el rol NO se toma de lo que manda el navegador: el registro público
        // siempre crea CLIENTE. Los administradores se asignan desde la base de datos.
        String rol = "CLIENTE";
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

    // RECUPERAR CONTRASEÑA (2 pasos)

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
                } else if (DEPURACION) {
                    // Al usuario se le responde igual por seguridad, pero acá en la consola se aclara
                    System.out.println("[DEPURACION] Recuperación: el mail " + email + " NO está registrado, no se envía nada.");
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

    // Sesiones: al confirmar el código el servidor entrega un "token" 

    // token = rol. Las rutas del administrador piden el token en el encabezado X-Token
    static final Map<String, String> sesiones = new ConcurrentHashMap<>();

    // token = {nombre, email}. Se usa para saber a quién mandarle el comprobante del pedido
    static final Map<String, String[]> datosSesion = new ConcurrentHashMap<>();

    static String crearSesion(String rol, String nombre, String email) {
        byte[] bytes = new byte[32];
        ALEATORIO.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        sesiones.put(token, rol);
        datosSesion.put(token, new String[] { nombre, email });
        return token;
    }

    static boolean esAdministrador(HttpExchange ex) {
        String token = ex.getRequestHeaders().getFirst("X-Token");
        return token != null && "ADMINISTRADOR".equals(sesiones.get(token));
    }

    static final String TEXTO = "text/plain; charset=UTF-8";
    static final String JSON = "application/json; charset=UTF-8";

    // "/api/productos/5" -> 5 ; "/api/productos" -> null
    static Integer idDeLaRuta(HttpExchange ex) {
        String[] partes = ex.getRequestURI().getPath().split("/");   // ["", "api", "productos", "5"]
        if (partes.length < 4) {
            return null;
        }
        try {
            return Integer.parseInt(partes[3]);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Identificador inválido.");
        }
    }

    // Texto obligatorio, sin espacios de más y con largo máximo
    static String campo(Map<String, String> datos, String clave, String etiqueta, int max) {
        String valor = datos.getOrDefault(clave, "").trim();
        if (valor.isEmpty()) {
            throw new IllegalArgumentException("Falta completar: " + etiqueta + ".");
        }
        if (valor.length() > max) {
            throw new IllegalArgumentException(etiqueta + " es demasiado largo (máximo " + max + " caracteres).");
        }
        return valor;
    }

    // CATÁLOGO (cualquier usuario con sesión: cliente o administrador) 
    // GET /api/catalogo -> misma lista que /api/productos, solo lectura

    static void catalogo(HttpExchange ex) throws IOException {
        String token = ex.getRequestHeaders().getFirst("X-Token");
        if (token == null || !sesiones.containsKey(token)) {
            enviar(ex, 401, TEXTO, "Sesión no válida. Iniciá sesión de nuevo.");
            return;
        }
        if (!"GET".equals(ex.getRequestMethod())) {
            enviar(ex, 405, TEXTO, "Operación no permitida.");
            return;
        }
        try {
            enviar(ex, 200, JSON, listarProductos());
        } catch (SQLException e) {
            e.printStackTrace();
            enviar(ex, 500, TEXTO, "Error de conexión con la base de datos.");
        }
    }

    // PEDIDOS (cliente o administrador con sesión)
    // GET  /api/pedidos = datos para mostrar antes de pedir (costo del envío)
    // POST /api/pedidos = registra el pedido, descuenta el stock y envía los mails
    //
    // En config.properties se pueden agregar (todas opcionales):
    // CORREO_PEDIDOS=mail donde llegan los pedidos (si no está, se usa CORREO_ORIGEN)
    // COSTO_ENVIO=    vacío = "se coordina con el local" | 0 = envío sin costo | 1500 = se suma al total
    // ALIAS_MERCADOPAGO=alias para las transferencias (se muestra en el comprobante)

    static final String LOCAL_DIRECCION = "Av. Piedrabuena 4990 - Villa Lugano";
    static final String LOCAL_TELEFONO = "4535-9379";
    static final String LOCAL_MAIL = "laplacita.p@gmail.com";

    static final String COSTO_ENVIO = CONFIG.getProperty("COSTO_ENVIO", "").trim();
    static final String ALIAS_MERCADOPAGO = CONFIG.getProperty("ALIAS_MERCADOPAGO", "").trim();

    static String correoPedidos() {
        String c = CONFIG.getProperty("CORREO_PEDIDOS", "").trim();
        return c.isEmpty() ? CORREO_ORIGEN : c;
    }

    // null = "a coordinar con el local"
    static BigDecimal costoEnvio() {
        if (COSTO_ENVIO.isEmpty()) {
            return null;
        }
        try {
            BigDecimal c = new BigDecimal(COSTO_ENVIO.replace(",", "."));
            return c.signum() >= 0 ? c : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    record LineaPedido(String nombre, String codigo, BigDecimal precio, int cantidad) {}

    // envio: null cuando es envío y el costo se coordina con el local
    record PedidoCreado(int numero, LocalDateTime fecha, List<LineaPedido> lineas,
                        BigDecimal subtotal, BigDecimal envio, BigDecimal total) {}

    static void pedidos(HttpExchange ex) throws IOException {
        String token = ex.getRequestHeaders().getFirst("X-Token");
        String[] quien = token == null ? null : datosSesion.get(token);   // {nombre, email}
        if (quien == null) {
            enviar(ex, 401, TEXTO, "Sesión no válida. Iniciá sesión de nuevo.");
            return;
        }

        if ("GET".equals(ex.getRequestMethod())) {
            BigDecimal envio = costoEnvio();
            enviar(ex, 200, JSON, "{\"costoEnvio\":" + (envio == null ? "null" : envio.toPlainString()) + "}");
            return;
        }
        if (!"POST".equals(ex.getRequestMethod())) {
            enviar(ex, 405, TEXTO, "Operación no permitida.");
            return;
        }

        try {
            Map<String, String> d = leerFormulario(ex);

            String telefono = campo(d, "telefono", "el teléfono", 30);
            if (!telefono.matches("[0-9+()\\- ]{6,30}")) {
                throw new IllegalArgumentException("Ingresá un teléfono válido (solo números).");
            }
            String entrega = d.getOrDefault("entrega", "");
            if (!entrega.equals("RETIRO") && !entrega.equals("ENVIO")) {
                throw new IllegalArgumentException("Elegí si retirás en el local o querés envío.");
            }
            String direccion = entrega.equals("ENVIO") ? campo(d, "direccion", "la dirección de entrega", 150) : null;
            String pago = d.getOrDefault("pago", "");
            if (!pago.equals("TRANSFERENCIA") && !pago.equals("EFECTIVO")) {
                throw new IllegalArgumentException("Elegí la forma de pago.");
            }
            String obs = d.getOrDefault("observaciones", "").trim();
            if (obs.length() > 255) {
                throw new IllegalArgumentException("Las observaciones son demasiado largas (máximo 255 caracteres).");
            }
            Map<Integer, Integer> items = leerItems(d.getOrDefault("items", ""));

            // Los precios y el stock se toman SIEMPRE de la base, nunca de lo que manda el navegador
            PedidoCreado p = guardarPedido(quien[0], quien[1], telefono, entrega, direccion, pago, obs, items);
            boolean mailCliente = enviarMailsPedido(p, quien[0], quien[1], telefono, entrega, direccion, pago, obs);

            enviar(ex, 200, JSON, "{\"numero\":\"" + String.format("%05d", p.numero())
                + "\",\"total\":" + p.total().toPlainString()
                + ",\"mailCliente\":" + mailCliente + "}");

        } catch (IllegalArgumentException e) {
            enviar(ex, 400, TEXTO, e.getMessage());
        } catch (SQLException e) {
            e.printStackTrace();
            enviar(ex, 500, TEXTO, "Error de conexión con la base de datos.");
        }
    }

    // "5:2,9:1" -> {5=2, 9=1}  (id del producto : cantidad)
    static Map<Integer, Integer> leerItems(String texto) {
        if (texto.isBlank()) {
            throw new IllegalArgumentException("El carrito está vacío.");
        }
        Map<Integer, Integer> items = new TreeMap<>();   // ordenado por id: siempre se bloquea en el mismo orden
        for (String par : texto.split(",")) {
            String[] x = par.split(":");
            try {
                int id = Integer.parseInt(x[0].trim());
                int cantidad = Integer.parseInt(x[1].trim());
                if (cantidad < 1 || cantidad > 999) {
                    throw new IllegalArgumentException("Hay una cantidad inválida en el carrito.");
                }
                items.merge(id, cantidad, Integer::sum);
            } catch (NumberFormatException | ArrayIndexOutOfBoundsException e) {
                throw new IllegalArgumentException("El carrito tiene datos inválidos.");
            }
        }
        if (items.size() > 50) {
            throw new IllegalArgumentException("El pedido tiene demasiados productos distintos (máximo 50).");
        }
        return items;
    }

    // Comprueba el stock, lo descuenta y guarda el pedido: todo junto o nada (transacción)
    static PedidoCreado guardarPedido(String nombre, String email, String telefono, String entrega,
            String direccion, String pago, String obs, Map<Integer, Integer> items) throws SQLException {

        LocalDateTime ahora = LocalDateTime.now();
        BigDecimal envio = entrega.equals("ENVIO") ? costoEnvio() : BigDecimal.ZERO;   // null = a coordinar

        try (Connection con = DriverManager.getConnection(URL, USUARIO_BD, CLAVE_BD)) {
            con.setAutoCommit(false);
            try {
                List<LineaPedido> lineas = new ArrayList<>();
                List<Integer> idsProducto = new ArrayList<>();
                BigDecimal subtotal = BigDecimal.ZERO;

                for (Map.Entry<Integer, Integer> e : items.entrySet()) {
                    String sel = "SELECT nombre, codigo, precio, stock FROM productos WHERE id_producto = ? FOR UPDATE";
                    try (PreparedStatement ps = con.prepareStatement(sel)) {
                        ps.setInt(1, e.getKey());
                        try (ResultSet rs = ps.executeQuery()) {
                            if (!rs.next()) {
                                throw new IllegalArgumentException(
                                    "Un producto del carrito ya no está disponible. Revisá tu carrito.");
                            }
                            String n = rs.getString("nombre");
                            int stock = rs.getInt("stock");
                            if (stock < e.getValue()) {
                                throw new IllegalArgumentException(stock == 0
                                    ? "\"" + n + "\" se quedó sin stock. Revisá tu carrito."
                                    : "De \"" + n + "\" solo quedan " + stock + ". Revisá tu carrito.");
                            }
                            BigDecimal precio = rs.getBigDecimal("precio");
                            lineas.add(new LineaPedido(n, rs.getString("codigo"), precio, e.getValue()));
                            idsProducto.add(e.getKey());
                            subtotal = subtotal.add(precio.multiply(BigDecimal.valueOf(e.getValue())));
                        }
                    }
                    try (PreparedStatement ps = con.prepareStatement(
                            "UPDATE productos SET stock = stock - ? WHERE id_producto = ?")) {
                        ps.setInt(1, e.getValue());
                        ps.setInt(2, e.getKey());
                        ps.executeUpdate();
                    }
                }

                BigDecimal total = subtotal.add(envio == null ? BigDecimal.ZERO : envio);

                int numero;
                String insPedido = "INSERT INTO pedidos (fecha, cliente_nombre, cliente_email, telefono, entrega, "
                    + "direccion, pago, costo_envio, total, observaciones) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
                try (PreparedStatement ps = con.prepareStatement(insPedido, Statement.RETURN_GENERATED_KEYS)) {
                    ps.setTimestamp(1, Timestamp.valueOf(ahora));
                    ps.setString(2, nombre);
                    ps.setString(3, email);
                    ps.setString(4, telefono);
                    ps.setString(5, entrega);
                    ps.setString(6, direccion);
                    ps.setString(7, pago);
                    ps.setBigDecimal(8, envio == null ? BigDecimal.ZERO : envio);
                    ps.setBigDecimal(9, total);
                    ps.setString(10, obs.isEmpty() ? null : obs);
                    ps.executeUpdate();
                    try (ResultSet claves = ps.getGeneratedKeys()) {
                        claves.next();
                        numero = claves.getInt(1);
                    }
                }

                String insItem = "INSERT INTO pedido_items (id_pedido, id_producto, nombre, codigo, precio, cantidad) "
                    + "VALUES (?, ?, ?, ?, ?, ?)";
                try (PreparedStatement ps = con.prepareStatement(insItem)) {
                    for (int i = 0; i < lineas.size(); i++) {
                        LineaPedido l = lineas.get(i);
                        ps.setInt(1, numero);
                        ps.setInt(2, idsProducto.get(i));
                        ps.setString(3, l.nombre());
                        ps.setString(4, l.codigo());
                        ps.setBigDecimal(5, l.precio());
                        ps.setInt(6, l.cantidad());
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }

                con.commit();
                return new PedidoCreado(numero, ahora, lineas, subtotal, envio, total);

            } catch (SQLException | RuntimeException e) {
                con.rollback();
                throw e;
            }
        }
    }

    // Mails del pedido

    static String pesos(BigDecimal n) {
        return String.format(Locale.forLanguageTag("es-AR"), "$%,.2f", n);
    }

    static String detallePedido(PedidoCreado p, String entrega) {
        StringBuilder t = new StringBuilder("DETALLE\r\n");
        for (LineaPedido l : p.lineas()) {
            t.append(l.nombre()).append(" (").append(l.codigo()).append(")\r\n")
             .append("   ").append(l.cantidad()).append(" x ").append(pesos(l.precio()))
             .append("  =  ").append(pesos(l.precio().multiply(BigDecimal.valueOf(l.cantidad())))).append("\r\n");
        }
        t.append("------------------------------------------\r\n")
         .append("Subtotal: ").append(pesos(p.subtotal())).append("\r\n");
        if (entrega.equals("ENVIO")) {
            t.append("Envío: ").append(p.envio() == null ? "a coordinar con el local (no incluido en el total)"
                                   : p.envio().signum() == 0 ? "sin costo" : pesos(p.envio())).append("\r\n");
        } else {
            t.append("Envío: retiro en el local (sin costo)\r\n");
        }
        t.append("TOTAL: ").append(pesos(p.total())).append("\r\n")
         .append("------------------------------------------\r\n");
        return t.toString();
    }

    static String textoEntrega(PedidoCreado p, String entrega, String direccion) {
        if (entrega.equals("ENVIO")) {
            return "Envío a domicilio: " + direccion + ".\r\n"
                 + (p.envio() == null ? "El costo del envío se coordina con el local.\r\n" : "");
        }
        return "Retiro en el local: " + LOCAL_DIRECCION + ".\r\n";
    }

    static String textoPago(String pago, String entrega) {
        if (pago.equals("TRANSFERENCIA")) {
            return "Transferencia (Mercado Pago).\r\n"
                 + (ALIAS_MERCADOPAGO.isEmpty()
                        ? "Pedinos el alias para transferir por teléfono (" + LOCAL_TELEFONO + ") o por mail (" + LOCAL_MAIL + ").\r\n"
                        : "Alias: " + ALIAS_MERCADOPAGO + "\r\n")
                 + "Cuando transfieras, enviá el comprobante de la transferencia a " + LOCAL_MAIL
                 + " o al " + LOCAL_TELEFONO + ".\r\n";
        }
        return "Efectivo: abonás " + (entrega.equals("ENVIO") ? "al recibir el pedido." : "al retirar el pedido en el local.") + "\r\n";
    }

    // Envía el aviso al local y el comprobante al cliente
    // Devuelve true si el comprobante salió (o si está en modo prueba y quedó en la consola)
    static boolean enviarMailsPedido(PedidoCreado p, String nombre, String email, String telefono,
            String entrega, String direccion, String pago, String obs) {

        String numero = String.format("%05d", p.numero());
        String fecha = p.fecha().format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"));
        String detalle = detallePedido(p, entrega);

        String paraElLocal = "NUEVO PEDIDO N° " + numero + "\r\n"
            + "Fecha: " + fecha + "\r\n\r\n"
            + "CLIENTE\r\n"
            + "Nombre: " + nombre + "\r\n"
            + "Mail: " + email + "\r\n"
            + "Teléfono: " + telefono + "\r\n\r\n"
            + detalle + "\r\n"
            + "ENTREGA\r\n" + textoEntrega(p, entrega, direccion) + "\r\n"
            + "FORMA DE PAGO\r\n" + (pago.equals("TRANSFERENCIA") ? "Transferencia (Mercado Pago)\r\n" : "Efectivo\r\n")
            + (obs.isEmpty() ? "" : "\r\nOBSERVACIONES DEL CLIENTE\r\n" + obs + "\r\n")
            + "\r\nEl stock de los productos ya fue descontado.\r\n";

        String paraElCliente = "Hola " + nombre + ",\r\n\r\n"
            + "Recibimos tu pedido. Guardá este comprobante.\r\n\r\n"
            + "PAÑALERA Y PERFUMERÍA \"LA PLACITA\"\r\n"
            + LOCAL_DIRECCION + "\r\n"
            + "Tel. " + LOCAL_TELEFONO + " | " + LOCAL_MAIL + "\r\n\r\n"
            + "COMPROBANTE DE PEDIDO N° " + numero + "\r\n"
            + "Fecha: " + fecha + "\r\n"
            + "Cliente: " + nombre + "\r\n"
            + "Teléfono: " + telefono + "\r\n\r\n"
            + detalle + "\r\n"
            + "ENTREGA\r\n" + textoEntrega(p, entrega, direccion) + "\r\n"
            + "FORMA DE PAGO\r\n" + textoPago(pago, entrega) + "\r\n"
            + "IMPORTANTE: el pedido se puede abonar por transferencia (Mercado Pago) o en efectivo.\r\n\r\n"
            + "Este comprobante no es una factura.\r\n";

        String asuntoLocal = "Nuevo pedido N° " + numero + " - " + nombre + " - " + pesos(p.total());
        String asuntoCliente = "Tu pedido N° " + numero + " en Pañalera La Placita";

        if (!correosActivados()) {
            System.out.println("[MODO PRUEBA] Mail al local (" + correoPedidos() + "):\r\n" + paraElLocal);
            System.out.println("[MODO PRUEBA] Mail al cliente (" + email + "):\r\n" + paraElCliente);
            return true;
        }

        try {
            enviarCorreo(correoPedidos(), asuntoLocal, paraElLocal);
            System.out.println("[CORREO] Pedido " + numero + " enviado al local");
        } catch (IOException e) {
            System.out.println("[CORREO] No se pudo avisar al local del pedido " + numero + ": " + e.getMessage());
        }
        try {
            enviarCorreo(email, asuntoCliente, paraElCliente);
            System.out.println("[CORREO] Comprobante del pedido " + numero + " enviado a " + email);
            return true;
        } catch (IOException e) {
            System.out.println("[CORREO] No se pudo enviar el comprobante " + numero + " a " + email + ": " + e.getMessage());
            return false;
        }
    }

    // PRODUCTOS (solo administrador)
    //   GET    /api/productos = lista
    //   POST   /api/productos = crea uno nuevo
    //   PUT    /api/productos/5 = edita el producto 5
    //   DELETE /api/productos/5 = elimina el producto 5

    record Producto(String nombre, String codigo, String categoria, BigDecimal precio, int stock) {}

    static void productos(HttpExchange ex) throws IOException {
        if (!esAdministrador(ex)) {
            enviar(ex, 401, TEXTO, "Sesión no válida. Iniciá sesión de nuevo.");
            return;
        }
        try {
            String metodo = ex.getRequestMethod();
            Integer id = idDeLaRuta(ex);

            if ("GET".equals(metodo) && id == null) {
                enviar(ex, 200, JSON, listarProductos());
            } else if ("POST".equals(metodo) && id == null) {
                insertarProducto(leerProducto(leerFormulario(ex)));
                enviar(ex, 201, TEXTO, "Producto guardado.");
            } else if ("PUT".equals(metodo) && id != null) {
                if (actualizarProducto(id, leerProducto(leerFormulario(ex)))) {
                    enviar(ex, 200, TEXTO, "Producto actualizado.");
                } else {
                    enviar(ex, 404, TEXTO, "El producto ya no existe.");
                }
            } else if ("DELETE".equals(metodo) && id != null) {
                if (eliminarProducto(id)) {
                    borrarDocumentos(id);   // también se borra su documento (PDF o Excel), si tenía
                    enviar(ex, 200, TEXTO, "Producto eliminado.");
                } else {
                    enviar(ex, 404, TEXTO, "El producto ya no existe.");
                }
            } else {
                enviar(ex, 405, TEXTO, "Operación no permitida.");
            }

        } catch (IllegalArgumentException e) {
            enviar(ex, 400, TEXTO, e.getMessage());
        } catch (SQLIntegrityConstraintViolationException e) {
            // La columna codigo es UNIQUE: MySQL rechaza un código repetido
            enviar(ex, 409, TEXTO, "Ya existe un producto con ese código.");
        } catch (SQLException e) {
            e.printStackTrace();
            enviar(ex, 500, TEXTO, "Error de conexión con la base de datos.");
        }
    }

    // Valida lo que llega del formulario y lo convierte en un Producto
    static Producto leerProducto(Map<String, String> d) {
        String nombre = campo(d, "nombre", "el nombre", 100);
        String codigo = campo(d, "codigo", "el código", 50);
        String categoria = campo(d, "categoria", "la categoría", 60);

        BigDecimal precio;
        int stock;
        try {
            precio = new BigDecimal(d.getOrDefault("precio", "").trim()).setScale(2, RoundingMode.HALF_UP);
            stock = Integer.parseInt(d.getOrDefault("stock", "").trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("El precio y la cantidad deben ser números.");
        }
        if (precio.signum() < 0 || stock < 0) {
            throw new IllegalArgumentException("El precio y la cantidad no pueden ser negativos.");
        }
        if (precio.compareTo(new BigDecimal("99999999.99")) > 0) {
            throw new IllegalArgumentException("El precio es demasiado alto.");
        }
        return new Producto(nombre, codigo, categoria, precio, stock);
    }

    // Devuelve la lista como JSON: [{"id":1,"nombre":"...",...}, ...]
    static String listarProductos() throws SQLException {
        String sql = "SELECT id_producto, nombre, codigo, categoria, precio, stock FROM productos ORDER BY nombre";
        StringBuilder json = new StringBuilder("[");

        try (Connection con = DriverManager.getConnection(URL, USUARIO_BD, CLAVE_BD);
             PreparedStatement ps = con.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {

            while (rs.next()) {
                if (json.length() > 1) {
                    json.append(",");
                }
                json.append("{\"id\":").append(rs.getInt("id_producto"))
                    .append(",\"nombre\":\"").append(escaparJson(rs.getString("nombre")))
                    .append("\",\"codigo\":\"").append(escaparJson(rs.getString("codigo")))
                    .append("\",\"categoria\":\"").append(escaparJson(rs.getString("categoria")))
                    .append("\",\"precio\":").append(rs.getBigDecimal("precio").toPlainString())
                    .append(",\"stock\":").append(rs.getInt("stock"))
                    .append("}");
            }
        }
        return json.append("]").toString();
    }

    static void insertarProducto(Producto p) throws SQLException {
        String sql = "INSERT INTO productos (nombre, codigo, categoria, precio, stock) VALUES (?, ?, ?, ?, ?)";

        try (Connection con = DriverManager.getConnection(URL, USUARIO_BD, CLAVE_BD);
             PreparedStatement ps = con.prepareStatement(sql)) {

            ps.setString(1, p.nombre());
            ps.setString(2, p.codigo());
            ps.setString(3, p.categoria());
            ps.setBigDecimal(4, p.precio());
            ps.setInt(5, p.stock());
            ps.executeUpdate();
        }
    }

    // Devuelve false si no existe un producto con ese id
    static boolean actualizarProducto(int id, Producto p) throws SQLException {
        String sql = "UPDATE productos SET nombre = ?, codigo = ?, categoria = ?, precio = ?, stock = ? "
                   + "WHERE id_producto = ?";

        try (Connection con = DriverManager.getConnection(URL, USUARIO_BD, CLAVE_BD);
             PreparedStatement ps = con.prepareStatement(sql)) {

            ps.setString(1, p.nombre());
            ps.setString(2, p.codigo());
            ps.setString(3, p.categoria());
            ps.setBigDecimal(4, p.precio());
            ps.setInt(5, p.stock());
            ps.setInt(6, id);
            return ps.executeUpdate() > 0;
        }
    }

    static boolean eliminarProducto(int id) throws SQLException {
        String sql = "DELETE FROM productos WHERE id_producto = ?";

        try (Connection con = DriverManager.getConnection(URL, USUARIO_BD, CLAVE_BD);
             PreparedStatement ps = con.prepareStatement(sql)) {

            ps.setInt(1, id);
            return ps.executeUpdate() > 0;
        }
    }

    //PROVEEDORES (solo administrador)
    // Mismas operaciones que productos, en /api/proveedores

    record Proveedor(String nombre, String contacto, String telefono, String direccion) {}

    static void proveedores(HttpExchange ex) throws IOException {
        if (!esAdministrador(ex)) {
            enviar(ex, 401, TEXTO, "Sesión no válida. Iniciá sesión de nuevo.");
            return;
        }
        try {
            String metodo = ex.getRequestMethod();
            Integer id = idDeLaRuta(ex);

            if ("GET".equals(metodo) && id == null) {
                enviar(ex, 200, JSON, listarProveedores());
            } else if ("POST".equals(metodo) && id == null) {
                insertarProveedor(leerProveedor(leerFormulario(ex)));
                enviar(ex, 201, TEXTO, "Proveedor guardado.");
            } else if ("PUT".equals(metodo) && id != null) {
                if (actualizarProveedor(id, leerProveedor(leerFormulario(ex)))) {
                    enviar(ex, 200, TEXTO, "Proveedor actualizado.");
                } else {
                    enviar(ex, 404, TEXTO, "El proveedor ya no existe.");
                }
            } else if ("DELETE".equals(metodo) && id != null) {
                if (eliminarProveedor(id)) {
                    enviar(ex, 200, TEXTO, "Proveedor eliminado.");
                } else {
                    enviar(ex, 404, TEXTO, "El proveedor ya no existe.");
                }
            } else {
                enviar(ex, 405, TEXTO, "Operación no permitida.");
            }

        } catch (IllegalArgumentException e) {
            enviar(ex, 400, TEXTO, e.getMessage());
        } catch (SQLException e) {
            e.printStackTrace();
            enviar(ex, 500, TEXTO, "Error de conexión con la base de datos.");
        }
    }

    static Proveedor leerProveedor(Map<String, String> d) {
        return new Proveedor(campo(d, "nombre", "el nombre", 100),
                             campo(d, "contacto", "el contacto", 100),
                             campo(d, "telefono", "el teléfono", 30),
                             campo(d, "direccion", "la dirección", 150));
    }

    static String listarProveedores() throws SQLException {
        String sql = "SELECT id_proveedor, nombre, contacto, telefono, direccion FROM proveedores ORDER BY nombre";
        StringBuilder json = new StringBuilder("[");

        try (Connection con = DriverManager.getConnection(URL, USUARIO_BD, CLAVE_BD);
             PreparedStatement ps = con.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {

            while (rs.next()) {
                if (json.length() > 1) {
                    json.append(",");
                }
                json.append("{\"id\":").append(rs.getInt("id_proveedor"))
                    .append(",\"nombre\":\"").append(escaparJson(rs.getString("nombre")))
                    .append("\",\"contacto\":\"").append(escaparJson(rs.getString("contacto")))
                    .append("\",\"telefono\":\"").append(escaparJson(rs.getString("telefono")))
                    .append("\",\"direccion\":\"").append(escaparJson(rs.getString("direccion")))
                    .append("\"}");
            }
        }
        return json.append("]").toString();
    }

    static void insertarProveedor(Proveedor v) throws SQLException {
        String sql = "INSERT INTO proveedores (nombre, contacto, telefono, direccion) VALUES (?, ?, ?, ?)";

        try (Connection con = DriverManager.getConnection(URL, USUARIO_BD, CLAVE_BD);
             PreparedStatement ps = con.prepareStatement(sql)) {

            ps.setString(1, v.nombre());
            ps.setString(2, v.contacto());
            ps.setString(3, v.telefono());
            ps.setString(4, v.direccion());
            ps.executeUpdate();
        }
    }

    static boolean actualizarProveedor(int id, Proveedor v) throws SQLException {
        String sql = "UPDATE proveedores SET nombre = ?, contacto = ?, telefono = ?, direccion = ? "
                   + "WHERE id_proveedor = ?";

        try (Connection con = DriverManager.getConnection(URL, USUARIO_BD, CLAVE_BD);
             PreparedStatement ps = con.prepareStatement(sql)) {

            ps.setString(1, v.nombre());
            ps.setString(2, v.contacto());
            ps.setString(3, v.telefono());
            ps.setString(4, v.direccion());
            ps.setInt(5, id);
            return ps.executeUpdate() > 0;
        }
    }

    static boolean eliminarProveedor(int id) throws SQLException {
        String sql = "DELETE FROM proveedores WHERE id_proveedor = ?";

        try (Connection con = DriverManager.getConnection(URL, USUARIO_BD, CLAVE_BD);
             PreparedStatement ps = con.prepareStatement(sql)) {

            ps.setInt(1, id);
            return ps.executeUpdate() > 0;
        }
    }

    // Documento de cada producto: PDF o Excel (solo administrador)
    // Se guardan en la carpeta "documentos" (al lado de "web", no es pública) como producto-<id>.pdf / .xlsx / .xls
    // Cada producto tiene como máximo un documento.
    //   GET    /api/documentos = lista: [{"id":1,"tipo":"pdf"},{"id":5,"tipo":"xlsx"}]
    //   GET    /api/documentos/5 = devuelve el documento del producto 5
    //   POST   /api/documentos/5 = sube (o reemplaza) el documento; el cuerpo es el archivo y X-Nombre su nombre
    //   DELETE /api/documentos/5 = elimina el documento del producto 5

    static final Path CARPETA_DOC = Paths.get("documentos").toAbsolutePath().normalize();
    static final int MAX_DOC_BYTES = 10 * 1024 * 1024;   // 10 MB
    static final String[] TIPOS_DOC = {"pdf", "xlsx", "xls"};

    static Path rutaDocumento(int idProducto, String tipo) {
        return CARPETA_DOC.resolve("producto-" + idProducto + "." + tipo);
    }

    // Devuelve "pdf", "xlsx" o "xls" según lo que tenga el producto, o null si no tiene nada
    static String tipoDocumento(int idProducto) {
        for (String tipo : TIPOS_DOC) {
            if (Files.isRegularFile(rutaDocumento(idProducto, tipo))) {
                return tipo;
            }
        }
        return null;
    }

    static void borrarDocumentos(int idProducto) throws IOException {
        for (String tipo : TIPOS_DOC) {
            Files.deleteIfExists(rutaDocumento(idProducto, tipo));
        }
    }

    static String tipoMime(String tipo) {
        switch (tipo) {
            case "pdf":  return "application/pdf";
            case "xlsx": return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            default:     return "application/vnd.ms-excel";
        }
    }

    static void documentos(HttpExchange ex) throws IOException {
        if (!esAdministrador(ex)) {
            enviar(ex, 401, TEXTO, "Sesión no válida. Iniciá sesión de nuevo.");
            return;
        }
        try {
            String metodo = ex.getRequestMethod();
            Integer id = idDeLaRuta(ex);

            if ("GET".equals(metodo) && id == null) {
                enviar(ex, 200, JSON, listarDocumentos());
            } else if ("GET".equals(metodo)) {
                String tipo = tipoDocumento(id);
                if (tipo == null) {
                    enviar(ex, 404, TEXTO, "Este producto no tiene documento.");
                } else {
                    String modo = "pdf".equals(tipo) ? "inline" : "attachment";
                    ex.getResponseHeaders().set("Content-Disposition", modo + "; filename=\"producto-" + id + "." + tipo + "\"");
                    enviar(ex, 200, tipoMime(tipo), Files.readAllBytes(rutaDocumento(id, tipo)));
                }
            } else if ("POST".equals(metodo) && id != null) {
                guardarDocumento(ex, id);
                enviar(ex, 201, TEXTO, "Documento guardado.");
            } else if ("DELETE".equals(metodo) && id != null) {
                if (tipoDocumento(id) == null) {
                    enviar(ex, 404, TEXTO, "Este producto no tiene documento.");
                } else {
                    borrarDocumentos(id);
                    enviar(ex, 200, TEXTO, "Documento eliminado.");
                }
            } else {
                enviar(ex, 405, TEXTO, "Operación no permitida.");
            }

        } catch (IllegalArgumentException e) {
            enviar(ex, 400, TEXTO, e.getMessage());
        }
    }

    static void guardarDocumento(HttpExchange ex, int idProducto) throws IOException {
        String cabecera = ex.getRequestHeaders().getFirst("X-Nombre");
        if (cabecera == null || cabecera.isBlank()) {
            throw new IllegalArgumentException("Falta el nombre del archivo.");
        }
        String nombre = URLDecoder.decode(cabecera, StandardCharsets.UTF_8).toLowerCase();
        String tipo = nombre.endsWith(".pdf") ? "pdf"
                    : nombre.endsWith(".xlsx") ? "xlsx"
                    : nombre.endsWith(".xls") ? "xls" : null;
        if (tipo == null) {
            throw new IllegalArgumentException("Solo se aceptan archivos PDF o Excel (.pdf, .xlsx, .xls).");
        }

        byte[] datos = ex.getRequestBody().readNBytes(MAX_DOC_BYTES + 1);
        if (datos.length > MAX_DOC_BYTES) {
            throw new IllegalArgumentException("El archivo supera los 10 MB.");
        }
        if (!contenidoValido(tipo, datos)) {
            throw new IllegalArgumentException("El archivo no es un " + ("pdf".equals(tipo) ? "PDF" : "Excel") + " válido.");
        }

        Files.createDirectories(CARPETA_DOC);
        borrarDocumentos(idProducto);                          // si ya tenía uno (de cualquier tipo), se reemplaza
        Files.write(rutaDocumento(idProducto, tipo), datos);
    }

    // Revisa el contenido real, no solo la extensión
    static boolean contenidoValido(String tipo, byte[] d) throws IOException {
        switch (tipo) {
            case "pdf":   // todo PDF empieza con "%PDF-"
                return d.length >= 5 && new String(d, 0, 5, StandardCharsets.ISO_8859_1).equals("%PDF-");
            case "xls":   // Excel antiguo: firma D0 CF 11 E0
                return d.length >= 8 && (d[0] & 0xFF) == 0xD0 && (d[1] & 0xFF) == 0xCF
                                     && (d[2] & 0xFF) == 0x11 && (d[3] & 0xFF) == 0xE0;
            default:      // xlsx: un ZIP que adentro tenga xl/workbook.xml
                try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(d))) {
                    for (ZipEntry e = zip.getNextEntry(); e != null; e = zip.getNextEntry()) {
                        if (e.getName().equals("xl/workbook.xml")) {
                            return true;
                        }
                    }
                }
                return false;
        }
    }

    static String listarDocumentos() throws IOException {
        StringBuilder json = new StringBuilder("[");
        if (Files.isDirectory(CARPETA_DOC)) {
            List<Path> archivos;
            try (Stream<Path> flujo = Files.list(CARPETA_DOC)) {
                archivos = flujo.toList();
            }
            for (Path f : archivos) {
                String nombre = f.getFileName().toString();
                if (nombre.matches("producto-\\d+\\.(pdf|xlsx|xls)")) {
                    String resto = nombre.substring("producto-".length());   // "5.xlsx"
                    int punto = resto.indexOf('.');
                    if (json.length() > 1) {
                        json.append(",");
                    }
                    json.append("{\"id\":").append(resto, 0, punto)
                        .append(",\"tipo\":\"").append(resto.substring(punto + 1)).append("\"}");
                }
            }
        }
        return json.append("]").toString();
    }

    // Códigos de verificación (se guardan en memoria)

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
                System.out.println("[CORREO] Código " + proposito + " enviado a " + email);
            } catch (IOException e) {
                System.out.println("No se pudo enviar el correo: " + e.getMessage());
                throw new CorreoException("No se pudo enviar el correo. Intentá de nuevo en unos minutos.");
            }
        } else {
            // Modo prueba: sin Gmail configurado el código se muestra en la consola
            System.out.println("[MODO PRUEBA] Código " + proposito + " para " + email + ": " + codigo);
        }

        if (DEPURACION) {
            System.out.println("[DEPURACION] Código " + proposito + " para " + email + ": " + codigo);
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

    // Base de datos 

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

    // Convierte "nombre=NomprePersona&email=a%40b.com&rol=CLIENTE" en un Map
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
        return texto.replace("\\", "\\\\").replace("\"", "\\\"")
                    .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    // Envío de correo (SMTP de Gmail, sin librerías externas)

    static boolean correosActivados() {
    return !CORREO_ORIGEN.isBlank() && !CLAVE_CORREO.isBlank()
        && !CORREO_ORIGEN.equals("tu_correo@gmail.com")
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

    // Contraseñas: se guardan "hasheadas" (PBKDF2 + sal), nunca en texto

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