This PCB is designed using KiCAD 9. 
For assembly, most of the parts can be purchased from DigiKey or Mouser, but SMA connectors and probe sockets are purchased from a local Japanese store. 

https://akizukidenshi.com/catalog/g/g101422/

https://akizukidenshi.com/catalog/g/g115480/

I recommend replacing them with your favorite ones if you plan to reuse this PCB design. 
There is a sense register for measuring current, but we did not use it because the noise seems too high. 

`Library.pretty` includes some libraries we prepared for our PCB, so you have to configure path for them. 

`jlcpcb` includes the Gerber file we used to order our PCB from JLCPCB. 
