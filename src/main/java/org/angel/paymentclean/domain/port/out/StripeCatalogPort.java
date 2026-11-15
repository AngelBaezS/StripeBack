package org.angel.paymentclean.domain.port.out;

import org.angel.paymentclean.domain.model.StripeCatalogResult;

public interface StripeCatalogPort {
    StripeCatalogResult crearProducto(String dbProductId, String nombre, String descripcion, Long precio);
    String actualizarPrecioDeProducto(String stripeProductId, String oldStripePriceId, Long nuevoPrecioEnCentavos);
}
