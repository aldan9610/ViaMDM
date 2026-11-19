# El proveedor AAR se llama por reflexión: sus clases no se tocan.
-keep class tunnel.** { *; }
# El cliente WebDAV y el pin TLS no tienen reflexión; nada que guardar.
