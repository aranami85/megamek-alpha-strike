# Alpha Strike test scenario for the MegaMek Alpha Strike mode.
# MegaMek Data (C) by The MegaMek Team is licensed under CC BY-NC-SA 4.0.
MMSVersion: 2
name: Alpha Strike - Grasslands Skirmish
planet: None
description: >
  Two lances meet on open grassland. Alpha Strike rules (standard damage, 15 turns).
  Click your unit, click a lit hex, turn, Move. In combat click a target and Fire.
gametype: AS
map: Grasslands BattleMats/32x17 Grasslands B BattleMat.board

factions:
  - name: Player
    team: 1
    deploy: W
    units:
      - type: ASElement
        fullname: Atlas AS7-D
      - type: ASElement
        fullname: Hunchback HBK-4G
      - type: ASElement
        fullname: Wolverine WVR-6R
      - type: ASElement
        fullname: Commando COM-2D

  - name: Bot
    team: 2
    deploy: E
    units:
      - type: ASElement
        fullname: Marauder MAD-3R
      - type: ASElement
        fullname: Archer ARC-2R
      - type: ASElement
        fullname: Phoenix Hawk PXH-1
      - type: ASElement
        fullname: Jenner JR7-D
