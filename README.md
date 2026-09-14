# ShieldSkip Native

Aplicación Android nativa de ShieldSkip. Usa Android VpnService para crear una VPN local y filtrar consultas DNS de dominios publicitarios/rastreadores.

## Importante
- Esto es un bloqueador DNS/VPN real, no una maqueta.
- No puede garantizar el bloqueo de todos los anuncios de todas las aplicaciones. En especial, anuncios servidos desde el mismo dominio que el contenido, por IP directa, DoH/DoT o mecanismos propietarios pueden no ser bloqueables mediante DNS.
- YouTube puede seguir mostrando anuncios; eliminarlos de forma fiable requiere técnicas distintas y no se puede prometer con un filtro DNS.
- Android requiere consentimiento del usuario para activar la VPN y muestra una notificación mientras la VPN está activa.

## Compilación web
El repositorio incluye `.github/workflows/build-apk.yml`. En GitHub: Actions -> Build ShieldSkip APK -> Run workflow. El APK aparece como artefacto `ShieldSkip-debug-apk`.
