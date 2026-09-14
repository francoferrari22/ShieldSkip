# ShieldSkip Native

ShieldSkip usa **SmartSkip por Accesibilidad**, no VPN, para mantener la conexión normal de las aplicaciones.

## Qué hace
- Interfaz oscura con escudo circular y navegación Escudo / Apps / Stats / Ajustes.
- Detecta controles accesibles de omitir/cerrar publicidad en aplicaciones compatibles.
- Intenta pulsar automáticamente textos como `Skip ad`, `Skip ads`, `Saltar anuncio`, `Omitir anuncio`, `Cerrar anuncio`.
- Permite elegir las aplicaciones protegidas.
- Lleva estadísticas de acciones realizadas.
- Pausa de 15 minutos con reanudación automática.

## Limitaciones reales
Android no permite que una aplicación externa cambie arbitrariamente la velocidad o el contenido del reproductor de otra aplicación. SmartSkip solo puede actuar cuando la aplicación expone un control accesible para omitir/cerrar. No garantiza eliminar todos los anuncios ni puede convertir cualquier anuncio en 10x/20x.

## Compilación web
El workflow de `.github/workflows/build-apk.yml` compila el APK con GitHub Actions.

Después de instalar el APK, hay que abrir ShieldSkip, entrar en Ajustes y habilitar **ShieldSkip SmartSkip** en Accesibilidad. Sin esa autorización el servicio no puede leer los controles de otras aplicaciones.
