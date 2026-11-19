import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Sistema de Información Operativo y Financiero - Parqueadero "Autos Colombia".
 * Iteración 3: Gestión de Pagos y Mensualidades.
 *
 * Integra: control de ingreso/salida y celdas (It. 1 y 2), RBAC (It. 2) y
 * módulo financiero (It. 3): mensualidades de 30 días, liquidación ocasional,
 * recargo por pernocta, ticket extraviado, recaudo multimedio, comprobantes
 * inmutables, reversos auditados, arqueo diario y apertura de talanquera.
 *
 * Compilar: javac Parqueadero.java
 * Ejecutar: java Parqueadero   (corre la matriz de pruebas TC-FIN-01 a TC-FIN-08)
 */
public class Parqueadero {

    // ------------------------------------------------------------------
    // Constantes de negocio
    // ------------------------------------------------------------------
    static final String NIT = "900.000.000-0"; // TODO: reemplazar por el NIT real
    static final LocalTime APERTURA = LocalTime.of(6, 0);
    static final LocalTime CIERRE = LocalTime.of(22, 0);
    static final int DIAS_MENSUALIDAD = 30;
    static final int DIAS_ALERTA_VENCIMIENTO = 5;

    // ------------------------------------------------------------------
    // Enumeraciones
    // ------------------------------------------------------------------
    enum Categoria {
        AUTOMOVIL, SUV;

        /** Solo se aceptan Automóvil y SUV (reglas de las iteraciones 1 y 3). */
        static Categoria desde(String texto) {
            String t = texto == null ? "" : texto.trim().toUpperCase(Locale.ROOT)
                    .replace("Ó", "O");
            switch (t) {
                case "AUTOMOVIL": return AUTOMOVIL;
                case "SUV": return SUV;
                default:
                    throw new ReglaNegocioException(
                            "Solo se admiten Automóviles y SUVs. Categoría recibida: " + texto);
            }
        }
    }

    enum Rol { ADMINISTRADOR, OPERARIO }

    enum MedioPago { EFECTIVO, TRANSFERENCIA_QR, TARJETA }

    enum EstadoCelda { DISPONIBLE, OCUPADA, MANTENIMIENTO }

    // ------------------------------------------------------------------
    // Excepciones
    // ------------------------------------------------------------------
    static class ReglaNegocioException extends RuntimeException {
        ReglaNegocioException(String m) { super(m); }
    }

    /** Equivalente a HTTP 403 (RNF-FIN-04). */
    static class AccesoDenegadoException extends RuntimeException {
        final int codigoHttp = 403;
        AccesoDenegadoException(String m) { super("HTTP 403 - " + m); }
    }

    // ------------------------------------------------------------------
    // Modelo
    // ------------------------------------------------------------------
    record Usuario(String id, String nombre, Rol rol) { }

    /** Tarifas vigentes. Inmutable: una actualización crea un nuevo objeto (solo aplica a futuro). */
    record Tarifas(BigDecimal horaAutomovil, BigDecimal horaSuv,
                   BigDecimal mensualidadAutomovil, BigDecimal mensualidadSuv,
                   BigDecimal recargoNocturno, int horasExtravio) {

        static Tarifas porDefecto() {
            return new Tarifas(cop(8000), cop(12000), cop(180000), cop(240000), cop(15000), 17);
        }

        BigDecimal hora(Categoria c) { return c == Categoria.SUV ? horaSuv : horaAutomovil; }

        BigDecimal mensualidad(Categoria c) {
            return c == Categoria.SUV ? mensualidadSuv : mensualidadAutomovil;
        }
    }

    static final class Celda {
        final String codigo;
        private EstadoCelda estado;
        private String placa;

        Celda(String codigo, EstadoCelda estado) { this.codigo = codigo; this.estado = estado; }

        EstadoCelda estado() { return estado; }
        String placa() { return placa; }

        void ocupar(String p) { estado = EstadoCelda.OCUPADA; placa = p; }
        void liberar() { estado = EstadoCelda.DISPONIBLE; placa = null; }
    }

    static final class Ticket {
        final long id;
        final String placa;
        final Categoria categoria;
        final String celda;
        final LocalDateTime ingreso;
        private LocalDateTime salida;

        Ticket(long id, String placa, Categoria categoria, String celda, LocalDateTime ingreso) {
            this.id = id; this.placa = placa; this.categoria = categoria;
            this.celda = celda; this.ingreso = ingreso;
        }

        boolean cerrado() { return salida != null; }
        void cerrar(LocalDateTime s) { this.salida = s; }
        void reabrir() { this.salida = null; }
    }

    static final class Suscripcion {
        final String placa;
        final Categoria categoria;
        final String titular;
        final LocalDate inicio;
        final LocalDate vencimiento; // inicio + 30 días (RF-FIN-01)

        Suscripcion(String placa, Categoria categoria, String titular, LocalDate inicio) {
            this.placa = placa; this.categoria = categoria; this.titular = titular;
            this.inicio = inicio; this.vencimiento = inicio.plusDays(DIAS_MENSUALIDAD);
        }

        boolean vigenteEn(LocalDate f) { return !f.isBefore(inicio) && !f.isAfter(vencimiento); }

        boolean seSuperpone(LocalDate otroInicio) {
            LocalDate otroFin = otroInicio.plusDays(DIAS_MENSUALIDAD);
            return !otroInicio.isAfter(vencimiento) && !otroFin.isBefore(inicio);
        }
    }

    /** Pago inmutable (RNF-FIN-02): un record no puede modificarse una vez creado. */
    record Pago(long consecutivo, LocalDateTime fecha, String placa, String concepto,
                BigDecimal base, BigDecimal recargo, BigDecimal total,
                MedioPago medio, BigDecimal recibido, BigDecimal cambio,
                String cajeroId, String documento, Long reversoDe) {

        String tirilla() {
            DateTimeFormatter f = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss");
            StringBuilder sb = new StringBuilder();
            sb.append("==============================================\n");
            sb.append("            AUTOS COLOMBIA S.A.\n");
            sb.append("            NIT ").append(NIT).append("\n");
            sb.append("==============================================\n");
            sb.append(String.format("Consecutivo : %08d%n", consecutivo));
            sb.append("Fecha/Hora  : ").append(fecha.format(f)).append("\n");
            sb.append("Placa       : ").append(placa).append("\n");
            sb.append("Concepto    : ").append(concepto).append("\n");
            if (documento != null) sb.append("Documento   : ").append(documento).append("\n");
            sb.append("----------------------------------------------\n");
            sb.append("Base        : ").append(fmt(base)).append("\n");
            sb.append("Recargo     : ").append(fmt(recargo)).append("\n");
            sb.append("TOTAL       : ").append(fmt(total)).append("\n");
            if (medio != null) {
                sb.append("Medio de pago: ").append(medio).append("\n");
                sb.append("Recibido    : ").append(fmt(recibido)).append("\n");
                sb.append("Cambio      : ").append(fmt(cambio)).append("\n");
            }
            if (reversoDe != null) sb.append("REVERSO DEL CONSECUTIVO ").append(reversoDe).append("\n");
            sb.append("Cajero      : ").append(cajeroId).append("\n");
            sb.append("==============================================\n");
            return sb.toString();
        }
    }

    /** Resultado del cálculo tarifario (RF-FIN-02, 03 y 04). */
    record Liquidacion(long horas, BigDecimal base, BigDecimal recargo, BigDecimal total,
                       boolean exento, boolean pernocta, boolean extraviado,
                       boolean mora, String concepto) { }

    // ------------------------------------------------------------------
    // Dispositivo externo: talanquera
    // ------------------------------------------------------------------
    interface ControladorTalanquera { void abrir(); }

    static class TalanqueraSimulada implements ControladorTalanquera {
        int aperturas = 0;
        boolean fallar = false; // para simular fallas de hardware en pruebas

        @Override public void abrir() {
            if (fallar) throw new IllegalStateException("Falla de comunicación con la talanquera");
            aperturas++;
        }
    }

    // ------------------------------------------------------------------
    // Estado del sistema
    // ------------------------------------------------------------------
    private final Map<String, Celda> celdas = new LinkedHashMap<>();
    private final Map<String, Ticket> activos = new LinkedHashMap<>();
    private final List<Ticket> historial = new ArrayList<>();
    private final List<Suscripcion> suscripciones = new ArrayList<>();
    private final List<Pago> pagos = new ArrayList<>();
    private final AtomicLong consecutivoPago = new AtomicLong(0);
    private final AtomicLong secuenciaTicket = new AtomicLong(0);
    private final ControladorTalanquera talanquera;
    private Tarifas tarifas = Tarifas.porDefecto();

    Parqueadero(ControladorTalanquera talanquera) {
        this.talanquera = talanquera;
        for (int i = 1; i <= 8; i++) celdas.put(String.format("A-%02d", i), new Celda(String.format("A-%02d", i), EstadoCelda.DISPONIBLE));
        for (int i = 1; i <= 4; i++) celdas.put(String.format("B-%02d", i), new Celda(String.format("B-%02d", i), EstadoCelda.DISPONIBLE));
    }

    // ------------------------------------------------------------------
    // Utilidades monetarias (RNF-FIN-01: BigDecimal, nunca double)
    // ------------------------------------------------------------------
    static BigDecimal cop(long valor) { return BigDecimal.valueOf(valor).setScale(2, RoundingMode.HALF_UP); }

    static String fmt(BigDecimal v) {
        return "$" + String.format(Locale.forLanguageTag("es-CO"), "%,.0f", v) + " COP";
    }

    private static String normalizarPlaca(String placa) {
        if (placa == null || placa.isBlank()) throw new ReglaNegocioException("La placa es obligatoria.");
        return placa.trim().toUpperCase(Locale.ROOT);
    }

    private static void exigirAutenticado(Usuario u) {
        if (u == null) throw new AccesoDenegadoException("Sesión no autenticada.");
    }

    private static void exigirAdmin(Usuario u) {
        exigirAutenticado(u);
        if (u.rol() != Rol.ADMINISTRADOR)
            throw new AccesoDenegadoException("Operación restringida al perfil Administrador.");
    }

    // ------------------------------------------------------------------
    // Ingreso de vehículos (Iteraciones 1 y 2, con consulta de abono - It. 3)
    // ------------------------------------------------------------------
    synchronized Ticket registrarIngreso(Usuario u, String placaTxt, String categoriaTxt,
                                         String celdaCodigo, LocalDateTime ingreso) {
        exigirAutenticado(u);
        String placa = normalizarPlaca(placaTxt);
        Categoria cat = Categoria.desde(categoriaTxt);
        LocalTime h = ingreso.toLocalTime();
        if (h.isBefore(APERTURA) || h.isAfter(CIERRE))
            throw new ReglaNegocioException("Fuera de la franja de atención (6:00 a.m. - 10:00 p.m.).");
        if (activos.containsKey(placa))
            throw new ReglaNegocioException("La placa " + placa + " ya tiene un ticket activo.");

        Celda celda = (celdaCodigo == null)
                ? celdas.values().stream().filter(c -> c.estado() == EstadoCelda.DISPONIBLE).findFirst()
                        .orElseThrow(() -> new ReglaNegocioException("No hay celdas disponibles."))
                : Optional.ofNullable(celdas.get(celdaCodigo))
                        .orElseThrow(() -> new ReglaNegocioException("La celda " + celdaCodigo + " no existe."));
        if (celda.estado() != EstadoCelda.DISPONIBLE)
            throw new ReglaNegocioException("La celda " + celda.codigo + " no está disponible.");

        celda.ocupar(placa);
        Ticket t = new Ticket(secuenciaTicket.incrementAndGet(), placa, cat, celda.codigo, ingreso);
        activos.put(placa, t);
        return t;
    }

    // ------------------------------------------------------------------
    // Mensualidades (RF-FIN-01, RF-FIN-02, RF-FIN-06)
    // ------------------------------------------------------------------
    Optional<Suscripcion> abonoVigente(String placa, LocalDate fecha) {
        return suscripciones.stream()
                .filter(s -> s.placa.equals(placa) && s.vigenteEn(fecha)).findFirst();
    }

    private Optional<Suscripcion> ultimaSuscripcion(String placa) {
        return suscripciones.stream().filter(s -> s.placa.equals(placa))
                .max((a, b) -> a.vencimiento.compareTo(b.vencimiento));
    }

    /**
     * Afilia o renueva un abono de 30 días. El estado "Vigente" solo se activa
     * después de confirmar el pago completo.
     */
    synchronized Pago afiliarOrenovar(Usuario u, String placaTxt, String categoriaTxt, String titular,
                                      LocalDate inicio, MedioPago medio, BigDecimal recibido,
                                      LocalDateTime ahora) {
        exigirAutenticado(u);
        String placa = normalizarPlaca(placaTxt);
        Categoria cat = Categoria.desde(categoriaTxt);
        if (titular == null || titular.isBlank())
            throw new ReglaNegocioException("El nombre del titular es obligatorio.");
        for (Suscripcion s : suscripciones) {
            if (s.placa.equals(placa) && s.seSuperpone(inicio))
                throw new ReglaNegocioException("Ya existe una mensualidad para " + placa
                        + " que se superpone con el rango solicitado (vence " + s.vencimiento + ").");
        }
        BigDecimal valor = tarifas.mensualidad(cat);
        Cobro cobro = cobrar(medio, valor, recibido);

        Pago pago = new Pago(consecutivoPago.incrementAndGet(), ahora, placa,
                "Mensualidad " + cat + " (30 días: " + inicio + " a " + inicio.plusDays(DIAS_MENSUALIDAD) + ")",
                valor, cop(0), valor, medio, cobro.recibido, cobro.cambio, u.id(), null, null);
        pagos.add(pago);                                   // 1. persistir pago
        suscripciones.add(new Suscripcion(placa, cat, titular.trim(), inicio)); // 2. activar
        return pago;
    }

    // ------------------------------------------------------------------
    // Cálculo tarifario (RF-FIN-02, 03 y 04)
    // ------------------------------------------------------------------
    Liquidacion calcular(Ticket t, LocalDateTime salida, boolean extraviado) {
        Optional<Suscripcion> abono = abonoVigente(t.placa, salida.toLocalDate());
        if (abono.isPresent()) {
            return new Liquidacion(0, cop(0), cop(0), cop(0), true, false, false, false,
                    "Salida exenta por mensualidad vigente (vence " + abono.get().vencimiento + ")");
        }
        boolean mora = ultimaSuscripcion(t.placa).isPresent();

        if (extraviado) {
            BigDecimal base = tarifas.hora(t.categoria).multiply(BigDecimal.valueOf(tarifas.horasExtravio()));
            return new Liquidacion(tarifas.horasExtravio(), base, cop(0), base, false, false, true, mora,
                    "Ticket extraviado - tarifa plena de " + tarifas.horasExtravio() + " horas (" + t.categoria + ")");
        }

        // Fracción iniciada = hora completa; mínimo 1 hora.
        long minutos = Math.max(1, Duration.between(t.ingreso, salida).toMinutes());
        long horas = (minutos + 59) / 60;
        BigDecimal base = tarifas.hora(t.categoria).multiply(BigDecimal.valueOf(horas));

        boolean pernocta = salida.toLocalTime().isAfter(CIERRE)
                || salida.toLocalDate().isAfter(t.ingreso.toLocalDate());
        BigDecimal recargo = pernocta ? tarifas.recargoNocturno() : cop(0);
        return new Liquidacion(horas, base, recargo, base.add(recargo), false, pernocta, false, mora,
                "Estadía ocasional " + t.categoria + " (" + horas + " h)" + (pernocta ? " + recargo por pernocta" : ""));
    }

    /** Vista previa de la liquidación sin persistir nada. */
    synchronized Liquidacion previsualizar(String placaTxt, LocalDateTime salida, boolean extraviado) {
        return calcular(ticketActivo(normalizarPlaca(placaTxt)), salida, extraviado);
    }

    private Ticket ticketActivo(String placa) {
        Ticket t = activos.get(placa);
        if (t != null) return t;
        boolean cerrado = historial.stream().anyMatch(h -> h.placa.equals(placa) && h.cerrado());
        throw new ReglaNegocioException(cerrado
                ? "El ticket de " + placa + " ya está cerrado: no admite un segundo pago."
                : "No existe un ticket activo para la placa " + placa + ".");
    }

    // ------------------------------------------------------------------
    // Cobro de salida (RF-FIN-03/04/05/06/07, RNF-FIN-06)
    // ------------------------------------------------------------------
    /**
     * Procesa la salida como una unidad indivisible: pago + cierre de ticket +
     * liberación de celda + apertura de talanquera. Si algo falla, se revierte todo.
     */
    synchronized Pago cobrarSalida(Usuario u, String placaTxt, LocalDateTime salida, boolean extraviado,
                                   String documento, MedioPago medio, BigDecimal recibido) {
        exigirAutenticado(u);
        String placa = normalizarPlaca(placaTxt);
        Ticket t = ticketActivo(placa);
        Liquidacion liq = calcular(t, salida, extraviado);

        if (liq.extraviado() && (documento == null || documento.isBlank()))
            throw new ReglaNegocioException("Ticket extraviado: el documento de identidad es obligatorio.");

        Cobro cobro = liq.exento() ? new Cobro(cop(0), cop(0)) : cobrar(medio, liq.total(), recibido);
        Pago pago = new Pago(consecutivoPago.incrementAndGet(), salida, placa, liq.concepto(),
                liq.base(), liq.recargo(), liq.total(), liq.exento() ? null : medio,
                cobro.recibido, cobro.cambio, u.id(), liq.extraviado() ? documento.trim() : null, null);

        Celda celda = celdas.get(t.celda);
        try {
            pagos.add(pago);
            t.cerrar(salida);
            celda.liberar();
            talanquera.abrir();
        } catch (RuntimeException e) {
            // Reversión de la transacción (pago aún no entregado al cliente).
            pagos.remove(pago);
            t.reabrir();
            celda.ocupar(placa);
            throw new ReglaNegocioException("Transacción revertida: " + e.getMessage());
        }
        activos.remove(placa);
        historial.add(t);
        return pago;
    }

    private record Cobro(BigDecimal recibido, BigDecimal cambio) { }

    /** Valida el medio de pago y calcula el cambio (RF-FIN-05). */
    private Cobro cobrar(MedioPago medio, BigDecimal total, BigDecimal recibido) {
        if (medio == null) throw new ReglaNegocioException("Debe seleccionar un medio de pago.");
        if (medio == MedioPago.EFECTIVO) {
            if (recibido == null || recibido.compareTo(total) < 0)
                throw new ReglaNegocioException("Monto insuficiente: se deben " + fmt(total)
                        + " y se recibieron " + fmt(recibido == null ? cop(0) : recibido) + ".");
            return new Cobro(recibido.setScale(2, RoundingMode.HALF_UP), recibido.subtract(total));
        }
        return new Cobro(total, cop(0));
    }

    // ------------------------------------------------------------------
    // Administración (RF-FIN-08, RNF-FIN-02, RNF-FIN-04)
    // ------------------------------------------------------------------
    synchronized void actualizarTarifas(Usuario u, Tarifas nuevas) {
        exigirAdmin(u);
        this.tarifas = nuevas; // aplica solo a transacciones futuras
    }

    /** Un pago confirmado no se edita ni se borra: se compensa con un reverso auditado. */
    synchronized Pago reversar(Usuario u, long consecutivo, String motivo, LocalDateTime ahora) {
        exigirAdmin(u);
        if (motivo == null || motivo.isBlank()) throw new ReglaNegocioException("El motivo del reverso es obligatorio.");
        Pago original = pagos.stream().filter(p -> p.consecutivo() == consecutivo).findFirst()
                .orElseThrow(() -> new ReglaNegocioException("No existe el pago " + consecutivo + "."));
        if (original.reversoDe() != null)
            throw new ReglaNegocioException("No se puede reversar un comprobante de reverso.");
        if (pagos.stream().anyMatch(p -> Long.valueOf(consecutivo).equals(p.reversoDe())))
            throw new ReglaNegocioException("El pago " + consecutivo + " ya fue reversado.");

        Pago reverso = new Pago(consecutivoPago.incrementAndGet(), ahora, original.placa(),
                "Reverso: " + motivo.trim(), original.base().negate(), original.recargo().negate(),
                original.total().negate(), original.medio(), original.total().negate(),
                cop(0), u.id(), original.documento(), consecutivo);
        pagos.add(reverso);
        return reverso;
    }

    /** Arqueo diario por medio de pago (RF-FIN-08). Los reversos restan. */
    synchronized Map<MedioPago, BigDecimal> arqueo(Usuario u, LocalDate dia) {
        exigirAdmin(u);
        Map<MedioPago, BigDecimal> r = new EnumMap<>(MedioPago.class);
        for (MedioPago m : MedioPago.values()) r.put(m, cop(0));
        for (Pago p : pagos) {
            if (p.medio() != null && p.fecha().toLocalDate().equals(dia))
                r.merge(p.medio(), p.total(), BigDecimal::add);
        }
        return r;
    }

    List<Pago> pagos() { return Collections.unmodifiableList(pagos); }
    Celda celda(String codigo) { return celdas.get(codigo); }

    // ------------------------------------------------------------------
    // Pruebas: matriz TC-FIN-01 a TC-FIN-08 (+ reglas de unicidad y atomicidad)
    // ------------------------------------------------------------------
    private static int fallos = 0;

    private static void check(String id, String descripcion, boolean ok) {
        System.out.printf("[%s] %-8s %s%n", ok ? "APROBADO" : "FALLIDO ", id, descripcion);
        if (!ok) fallos++;
    }

    private static boolean lanza(Class<? extends RuntimeException> tipo, Runnable r) {
        try { r.run(); return false; } catch (RuntimeException e) { return tipo.isInstance(e); }
    }

    public static void main(String[] args) {
        TalanqueraSimulada barrera = new TalanqueraSimulada();
        Parqueadero p = new Parqueadero(barrera);
        Usuario operario = new Usuario("OP-001", "Operario Turno 1", Rol.OPERARIO);
        Usuario admin = new Usuario("AD-001", "Administrador", Rol.ADMINISTRADOR);
        LocalDate hoy = LocalDate.of(2026, 11, 19);

        System.out.println("=== Parqueadero Autos Colombia - Pruebas Iteración 3 ===\n");

        // TC-FIN-01: afiliación mensual con QR
        Pago afil = p.afiliarOrenovar(operario, "JFB-412", "Automóvil", "Juan Becerra", hoy,
                MedioPago.TRANSFERENCIA_QR, cop(180000), hoy.atTime(7, 30));
        check("TC-FIN-01", "Afiliación de abono mensual (30 días) por QR",
                afil.total().compareTo(cop(180000)) == 0
                        && p.abonoVigente("JFB-412", hoy.plusDays(30)).isPresent()
                        && p.abonoVigente("JFB-412", hoy.plusDays(31)).isEmpty());
        System.out.println(afil.tirilla());

        // TC-FIN-02: salida sin cobro por abono vigente
        p.registrarIngreso(operario, "JFB-412", "Automóvil", "A-03", hoy.atTime(8, 0));
        Pago exento = p.cobrarSalida(operario, "JFB-412", hoy.atTime(11, 0), false, null, null, null);
        check("TC-FIN-02", "Salida sin cobro por abono vigente",
                exento.total().signum() == 0 && p.celda("A-03").estado() == EstadoCelda.DISPONIBLE
                        && barrera.aperturas == 1);

        // TC-FIN-03: SUV con pernocta: 5 x 12.000 + 15.000 = 75.000, cambio 5.000
        p.registrarIngreso(operario, "RNT-820", "SUV", "B-01", hoy.atTime(17, 30));
        Pago suv = p.cobrarSalida(operario, "RNT-820", hoy.atTime(22, 30), false, null,
                MedioPago.EFECTIVO, cop(80000));
        check("TC-FIN-03", "SUV con recargo de pernocta y cambio",
                suv.total().compareTo(cop(75000)) == 0 && suv.cambio().compareTo(cop(5000)) == 0
                        && p.celda("B-01").estado() == EstadoCelda.DISPONIBLE && barrera.aperturas == 2);

        // TC-FIN-04: ticket extraviado: 17 x 8.000 = 136.000 con CC
        p.registrarIngreso(operario, "LMN-309", "Automóvil", "A-06", hoy.atTime(9, 0));
        Pago extra = p.cobrarSalida(operario, "LMN-309", hoy.atTime(15, 0), true, "71784512",
                MedioPago.TARJETA, null);
        check("TC-FIN-04", "Ticket extraviado (17 h) con documento",
                extra.total().compareTo(cop(136000)) == 0 && "71784512".equals(extra.documento())
                        && p.celda("A-06").estado() == EstadoCelda.DISPONIBLE);
        check("TC-FIN-04b", "Extravío sin documento es rechazado",
                lanza(ReglaNegocioException.class, () -> {
                    p.registrarIngreso(operario, "ZZZ-000", "Automóvil", "A-07", hoy.atTime(9, 0));
                    p.cobrarSalida(operario, "ZZZ-000", hoy.atTime(10, 0), true, " ",
                            MedioPago.TARJETA, null);
                }));

        // TC-FIN-05: efectivo insuficiente (3 h x 8.000 = 24.000, recibe 20.000)
        p.registrarIngreso(operario, "XYZ-123", "Automóvil", "A-02", hoy.atTime(14, 0));
        int aperturasAntes = barrera.aperturas;
        boolean rechazado = lanza(ReglaNegocioException.class,
                () -> p.cobrarSalida(operario, "XYZ-123", hoy.atTime(17, 0), false, null,
                        MedioPago.EFECTIVO, cop(20000)));
        check("TC-FIN-05", "Rechazo por monto insuficiente en efectivo",
                rechazado && barrera.aperturas == aperturasAntes
                        && p.celda("A-02").estado() == EstadoCelda.OCUPADA);

        // TC-FIN-06: vehículo no autorizado
        check("TC-FIN-06", "Rechazo de Motocicleta",
                lanza(ReglaNegocioException.class, () -> p.afiliarOrenovar(operario, "KTR-99E",
                        "Motocicleta", "Titular", hoy, MedioPago.TARJETA, null, hoy.atTime(9, 0))));

        // TC-FIN-07: RBAC
        Tarifas nuevas = new Tarifas(cop(9000), cop(13000), cop(190000), cop(250000), cop(16000), 17);
        check("TC-FIN-07", "Operario no puede ajustar tarifas (HTTP 403)",
                lanza(AccesoDenegadoException.class, () -> p.actualizarTarifas(operario, nuevas)));

        // TC-FIN-08: arqueo
        Map<MedioPago, BigDecimal> arq = p.arqueo(admin, hoy);
        BigDecimal total = arq.values().stream().reduce(cop(0), BigDecimal::add);
        check("TC-FIN-08", "Arqueo: QR 180.000, Efectivo 75.000, Tarjeta 136.000, Total 391.000",
                arq.get(MedioPago.TRANSFERENCIA_QR).compareTo(cop(180000)) == 0
                        && arq.get(MedioPago.EFECTIVO).compareTo(cop(75000)) == 0
                        && arq.get(MedioPago.TARJETA).compareTo(cop(136000)) == 0
                        && total.compareTo(cop(391000)) == 0);

        // RF-FIN-06: unicidad transaccional
        check("RF-FIN-06a", "Segundo pago sobre ticket cerrado es bloqueado",
                lanza(ReglaNegocioException.class, () -> p.cobrarSalida(operario, "RNT-820",
                        hoy.atTime(23, 0), false, null, MedioPago.TARJETA, null)));
        check("RF-FIN-06b", "Suscripciones superpuestas son bloqueadas",
                lanza(ReglaNegocioException.class, () -> p.afiliarOrenovar(operario, "JFB-412",
                        "Automóvil", "Juan Becerra", hoy.plusDays(10), MedioPago.TARJETA, null,
                        hoy.atTime(9, 0))));

        // RNF-FIN-06: atomicidad ante falla de talanquera
        barrera.fallar = true;
        boolean revertido = lanza(ReglaNegocioException.class,
                () -> p.cobrarSalida(operario, "XYZ-123", hoy.atTime(17, 0), false, null,
                        MedioPago.TARJETA, null));
        barrera.fallar = false;
        long pagosXyz = p.pagos().stream().filter(x -> x.placa().equals("XYZ-123")).count();
        check("RNF-FIN-06", "Falla de talanquera revierte pago, ticket y celda",
                revertido && pagosXyz == 0 && p.celda("A-02").estado() == EstadoCelda.OCUPADA);

        // RNF-FIN-02: reverso auditado (no se borra el pago original)
        int antes = p.pagos().size();
        Pago rev = p.reversar(admin, suv.consecutivo(), "Cobro duplicado en caja", hoy.atTime(23, 0));
        check("RNF-FIN-02", "Reverso auditado sin borrar el pago original",
                p.pagos().size() == antes + 1 && rev.total().compareTo(cop(-75000)) == 0
                        && rev.reversoDe() == suv.consecutivo());

        System.out.println("\nResultado: " + (fallos == 0 ? "TODAS LAS PRUEBAS APROBADAS" : fallos + " PRUEBA(S) FALLIDA(S)"));
        System.exit(fallos == 0 ? 0 : 1);
    }
}
