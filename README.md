# PV Better Mute (Fabric 1.21.1)

Port de `pv-addon-mute` a Fabric para trabajar una rama nueva sin tocar el proyecto NeoForge original.

## Comandos
- `/vcmod mute <jugador> [razon]`
- `/vcmod tempmute <jugador> <duracion> [razon]` (ej: `30s`, `10m`, `2h`, `1d`)
- `/vcmod unmute <jugador>`
- `/vcmod lockdown on`
- `/vcmod lockdown off`
- `/vcmod allow <jugador>`
- `/vcmod disallow <jugador>`
- `/vcmod status`
- `/vcmod list`

## Requisitos
- Java 21
- Minecraft/Fabric 1.21.1
- Fabric Loader `0.16.14+`
- Fabric API `0.116.8+1.21.1`
- Plasmo Voice `2.1.8+` (dependencia obligatoria)

## Build
```bash
./gradlew build
```

## Persistencia
El mod guarda estado en:
- `config/pv-better-mute-state.json`

Incluye:
- estado de lockdown
- lista de `allow`
- mutes manuales permanentes/temporales

## Dev Run
Para pruebas locales, este repo usa:
- `libs/plasmovoice-fabric-1.21-2.1.8.jar` como `modLocalRuntime`
- `libs/fabric-permissions-api-0.3.1.jar` como `modLocalRuntime`

Ejecuta:
```bash
./gradlew runClient
```

## Admin HUD
- Tecla por defecto: `O`
- Abre panel con jugadores online y botones:
  - `Mute` / `Unmute`
  - `Allow` / `Disallow`
  - `Lockdown ON` / `Lockdown OFF`
  - `Status`
