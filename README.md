# NTM-NEXT Reimagined Arms

Addon for HBM's Nuclear Tech Mod (NTM-NEXT, NeoForge 26.2) that makes its guns feel like a modern
shooter mod without new animations and without touching the NTM jar. Mixins only.

Client:
- per-frame camera recoil with a sharp kick, short hold and settle, plus a light roll shake;
- gun model kick per shot: muzzle rise, push back, random yaw and roll, pivoting on the grip;
- gun moved toward the screen centre and closer in hip fire;
- the player's arms holding the gun with both hands, placed on the gun's own geometry;
- off-hand item hidden while holding a gun;
- corner ammo HUD: gun silhouette rendered from the NTM model, magazine count, reserve, durability.

Server: hold right click to aim instead of toggling.

The same jar goes on the client and the server.

## Settings

`config/ntm-gunfeel.properties`, re-read while the game runs. Hand positions per gun:
`/gunfeel grip right|left <x> <y> <z>`, `/gunfeel grip show`, `/gunfeel grip reset`
(z = stock 0 to muzzle 1; x, y across the gun there, y 0 = underside).

## Build

`./build.sh` compiles against the libraries of a local Prism Launcher NeoForge 26.2 instance.

## License

Copyright (C) 2026 ACupOfChicory

The code is free software: you can redistribute it and/or modify it under the terms of the GNU General Public
License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later
version. See [LICENSE](LICENSE).

Sounds under `res/assets/ntmgunfeel/sounds/` come from freesound.org and keep their own licenses (CC0 1.0 or
CC BY 3.0/4.0); authors and links are in [CREDITS.txt](res/assets/ntmgunfeel/sounds/bullet/CREDITS.txt).
