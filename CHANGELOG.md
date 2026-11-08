MegaMek Alpha Strike mode - change log
=======================================
Newest first. Rule page numbers are from Alpha Strike: Commander's Edition.


0.6.1  (Nov 8, 2026) - code tidy-up (MegaMek coding style)
----------------------------------------------------------
- The code now follows MegaMek's coding style guide: MegaMek's full license header in every
  file, explicit parentheses in conditions, JavaDoc on public methods, LOGGER naming and
  templated log messages, lines no longer than 120 characters.
- All text you see (window, dialogs, battle report) moved to messages.properties, so the mode
  can be translated like the rest of MegaMek.
- No changes to the rules or how the game plays.


0.6.0  (Nov 8, 2026) - cleaner AI bot setup
-------------------------------------------
- The AI bots' code was reorganised: the AI program is found with -Das.engine and its trained
  model file is now called as_ai_model.bin. Other bot kinds can be added from a separate jar
  (add-on bots), without changing the mode itself.
- The source zip now holds only the source, readme, license, change log and build script.
- Chat box tooltip simplified.


0.5.1  (Nov 2, 2026) - rear and turret weapons
----------------------------------------------
- REAR weapons (p. 79): a unit with REAR#/#/# can shoot a target outside its front arc with its
  rear damage, at +1 to hit. It can do that in the same turn as its normal attack, but the normal
  attack loses as much damage as the rear weapons can deal. No overheat or heat on rear attacks.
  In the window, rear targets are tagged "R9+": click one to add the rear attack, then Fire.
- Turrets (p. 80): a unit with TUR(#/#/#) can attack targets outside its front arc using its
  turret damage instead of its normal damage. Turret targets are tagged "8+T".
- AMS on a turret unit also works against attacks from behind.
- The bots use rear weapons too (when there's nothing in front, or the rear shot is the easier
  one and the front attack still keeps some damage).


0.5.0  (Nov 2, 2026) - special abilities, ECM and C3
----------------------------------------------------
New rules
- C3 networks (pp. 80-82): every linked unit attacks with the range modifier of the closest
  linked teammate that can see the target. Damage still uses the attacker's own range, and it
  still needs its own line of sight.
    * Standard C3: a master runs up to 3 units. A company master can run up to 3 lance masters,
      each with its own slaves (12 units max).
    * Boosted C3 (C3BSM/C3BSS): only Angel ECM jams it.
    * C3i: up to 6 units, no master. Nova CEWS: up to 3 units, only enemy Nova jams it.
- ECM, Angel ECM, Watchdog and Nova: 12" (6 hex) jamming bubble. Light ECM: 2" (1 hex).
    * A master inside an enemy bubble takes its whole network down.
    * A unit whose link to its master runs through a bubble is cut off on its own.
- Stealth (STL): +1 to hit it at Medium range, +2 at Long. Its own C3 and ECM don't work.
- Mimetic armor (MAS / LMAS): +3 / +2 to hit it with weapons if it stood still this turn.
- AMS / RAMS: -1 damage (to a minimum of 1) from attackers with IF, SRM or LRM, from the front.
- Shield (SHLD): -1 damage from weapon and physical attacks; +1 to hit on its own weapon attacks.
- TSM: when running hot (heat 1+), +2" Move (heat 1 costs no Move) and +1 physical damage.
- Industrial TSM: +1 physical damage, +2 to hit on physical attacks.
- Critical-resistant (CR): -2 on critical hit rolls.
- Fire resistant (FR): no heat from HT attacks.

New in the lobby
- "C3 Groups..." button (under Load/Save list). Click New group, click the units you want, then
  Save group.
    * If you pick slaves without a master, it says "Must select at least 1 master."
    * Units that no longer fit grey out once the group is full.
    * With 2 or more masters you pick the company master.
    * You can also edit or break up groups.
- These are MegaMek's normal C3 links: they show in the unit list, save with your MUL lists and
  carry into the game.

New on the map
- "Show on map" switches at the top of the game window: ECM areas and C3 links (remembered
  between games).
    * ECM: a tinted halo in the owner's colour (dashed edge = Angel ECM).
    * C3: a solid line = linked, a red dashed line = cut off.
- Tooltips list each unit's special abilities and C3 state.
- Units are always drawn on top of halos, lines and movement hexes.

Not yet
- Indirect fire, TAG/Narc, the C3 emergency master taking over, and switching stealth off.
- The AI bots follow the new rules but don't use C3 or ECM on purpose yet.


0.4.7  (Nov 2, 2026) - deployment and "why can't I shoot?"
----------------------------------------------------------
- New Deployment phase before round 1: click your unit, click a lit hex in your 3-hex strip,
  turn it, then click Done.
- Click an enemy you can't attack and the status bar says why:
    * out of range
    * outside your front arc
    * no line of sight (names the hex in the way)
    * needs more than 12
- A chosen target shows the full to-hit breakdown (skill, TMM, range, woods...). The map
  tooltip shows it too in the Combat Phase.
- Fix: a unit destroyed during the Combat Phase now gets the red X at once and shows
  "destroyed (fires back)" in the list. It can't be picked as a target any more. As the rules
  say, it still shoots back this turn and leaves the map in the End Phase.


0.4.5  (Oct 29, 2026)
---------------------
- Unit pictures turn to face the hex edge they face, instead of an arrow.


0.4.4  (Oct 29, 2026)
---------------------
- Fix: the AS card could show up as a "ghost" picture on the map, and come back at the end of a
  round.


0.4.3  (Oct 29, 2026)
---------------------
- Fix: the map could get stuck at window size, with old pictures left on it until you zoomed.
- Clicking a unit in the side lists scrolls the map to it.
- The AS card only redraws when its unit or its damage changes.


0.4.0 - 0.4.2  (Oct 29, 2026)
-----------------------------
- Unit pictures on the map (MegaMek's 'Mech art in the owner's camo), with name, armor and
  structure pips, and heat.
    * Outline: yellow = selected, cyan = looked at, red = target.
    * Units that already acted are darker.
- Unit lists on the left, one per side: picture, armor/structure, heat, PV, and whether each
  unit has moved / attacked yet. Click one to highlight it on the map and see its AS card.
- Chat box in the game window.
- Light-theme fix for the unit lists.
- Faster AI v3 engine (as_ai_engine.exe) for the bots.


0.3.0  (Oct 29, 2026) - bot choice and Random Army in PV
--------------------------------------------------------
- Add Bot in an Alpha Strike lobby asks which bot plays: AI v3 (strongest), AI v2.1, or the
  scripted bot, and which team.
- Random Army works in PV:
    * PV ranges instead of BV.
    * The unit list shows PV and Alpha Strike movement.
    * "PV Total" instead of "BV Total".


0.2.0  (Oct 28, 2026) - MegaMek's own lobby
-------------------------------------------
- New main-menu entry "Host Alpha Strike Game": the normal MegaMek lobby for teams and lists.
  When everyone clicks Done, the units turn into their Alpha Strike cards and the battle starts.
- The lobby shows the AS card line, skill and PV for every unit.


0.1.0  (Oct 28, 2026) - first playable version
----------------------------------------------
- Alpha Strike on MegaMek's hex maps (1 hex = 2"), two sides, alternating movement and combat,
  standard damage, overheat, criticals, heat, 15 turns (most strength left wins on time).
- Woods +1 to hit (3 woods hexes block line of sight), partial cover +1.
- Special abilities: CASE, CASE II, ENE, OVL, MEL, BAR, ARM, HT, JMPS/JMPW.
