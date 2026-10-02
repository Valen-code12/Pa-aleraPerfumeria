// Clase abstracta: lo que tienen en común todos los usuarios del sistema
public abstract class Persona {
    private String nombre;
    private String email;
 
    protected Persona(String nombre, String email) {
        this.nombre = nombre;
        this.email = email;
    }
 
    public String getNombre() { return nombre; }
    public String getEmail() { return email; }
 
    // Cada subclase decide estos tres datos (polimorfismo)
    public abstract String getRol();     // como se guarda en la base: "ADMINISTRADOR" o "CLIENTE"
    public abstract String getTipo();    // como se escribe en los mails: "administrador" o "cliente"
    public abstract String getPagina();  // a qué página va después de iniciar sesión
 
    // Crea el objeto correcto según el rol guardado en la base de datos
    public static Persona crear(String rol, String nombre, String email) {
        switch (rol) {
            case "ADMINISTRADOR": return new Administrador(nombre, email);
            case "CLIENTE":       return new Cliente(nombre, email);
            default: throw new IllegalArgumentException("Rol inválido.");
        }
    }
 
    @Override
    public String toString() {
        return getRol() + "{nombre='" + nombre + "', email='" + email + "'}";
    }
}
 