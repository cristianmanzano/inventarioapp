# Decisiones de diseño

## Supuestos asumidos

- La reserva se representa como un bloqueo temporal sobre unidades y no descuenta stock físico del almacén hasta que la compra se confirma.
- El inventario disponible se calcula como: stock total menos reservas activas no expiradas.
- Las reservas expiran en función de la categoría del producto y se eliminan automáticamente al consultarse o al mutar el estado del servicio.
- Las alertas de compras solo se disparan cuando el inventario pasa a estar en el rango de 5 unidades o menos y no se repiten mientras ese SKU no se repone por encima del umbral.
- Un `orderId` se interpreta como único para una reserva activa de un solo producto, que es la regla del contrato.

## Qué dejé fuera por ahora

- Persistencia en base de datos: dado que el README indica que es un servicio en memoria y que se migrará más adelante, no añadí almacenamiento duradero.
- Reintentos o compensaciones para pagos fallidos: este servicio asume que la confirmación del pago llega de forma determinista y que la liberación por expiración se encarga de la reserva vencida.

## Cambios que haría antes de producción

- Migrar la estructura de datos a un almacenamiento transaccional con TTL y control de concurrencia.
- Añadir pruebas de integración para casos de alta concurrencia y reservas vencidas en múltiples instancias.
- Separar la lógica de dominio en módulos más explícitos para facilitar cambios de reglas de negocio sin tocar el contrato público.
- Reforzar el control de alertas con un canal de notificación configurable por SKU y tipo de evento, en lugar de un único listener.
