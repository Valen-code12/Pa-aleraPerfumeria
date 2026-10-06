public class Cliente extends Persona {

    public Cliente(String nombre, String email) {
        super(nombre, email);
    }

    @Override
    public String getRol() { return "CLIENTE"; }

    @Override
    public String getTipo() { return "cliente"; }

    @Override
    public String getPagina() { return "Cliente.html"; }

    // Más adelante: agregarAlCarrito(), verPedidos(), etc.
}