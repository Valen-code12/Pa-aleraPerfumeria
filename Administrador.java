public class Administrador extends Persona {

    public Administrador(String nombre, String email) {
        super(nombre, email);
    }

    @Override
    public String getRol() { return "ADMINISTRADOR"; }

    @Override
    public String getTipo() { return "administrador"; }

    @Override
    public String getPagina() { return "Administrador.html"; }

    // Más adelante: gestionarStock(), cargarProducto(), etc.
}