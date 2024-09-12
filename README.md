# picoFace Phone

This repository contains a support program for the picoFace. The picoFace is an inexpensive device for the Sinclair ZX Spectrum that use a Raspberry Pi Pico to provide a Multiface style support. This application is allows snapshots to be sent to the picoFace via an OTG cable to the picoFace.


![picoFace Phone](./screenshot.png)

See the main picoFace repository for more info https://github.com/brianapps/picoface_poc.

## Info

The program requires a suitable `snaps.zip` file containing a collection of snapshots to load. This file should be copied to the external folder for the application. The application will show an error if the snaps.zip is not found to help you locate where this folder might be on your particular device.

To create this file see the `create_snaps_zip.py` script from the [main repo]()

## License

picoFace is free software: you can redistribute it and/or modify it under the terms of
the GNU General Public License as published by the Free Software Foundation, either
version 3 of the License, or (at your option) any later version.

picoFace is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; 
without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
See the GNU General Public License for more details.

A copy of the license is provided in the [LICENSE](./LICENSE) file.

