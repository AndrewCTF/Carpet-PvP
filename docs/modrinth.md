# Carpet PvP

Carpet with PvP practice built in. It adds server-side bots that fight back, so you can practise on your own
server at any hour without a sparring partner. Everything runs on the server: a vanilla client can join and
fight.

![A mace bot smashing a sword bot](https://raw.githubusercontent.com/AndrewCTF/Carpet-PvP/main/docs/images/duel-mace.png)

## What you get

- **Bots that fight in five modes**: `sword`, `smp`, `mace`, `crystal` and `ranged`, each with its own kit.
- **Five difficulty levels**, from `beginner` to `expert`. They differ in reaction time, aim, click rate and
  which techniques the bot uses, and every setting can be changed per bot.
- **One command to start**: `/auto-setup sword average` builds a fenced arena next to you, gives you and a bot
  the same kit, counts down and starts the fight. `/auto-setup stop` puts every block and your inventory back.
- **Practice tools**: drills that score you, team and free-for-all matches, spectating a bot, fight traces,
  built-in kits and a kit editor.
- **Chest menus** (`/bot gui`) that work with a vanilla client.
- **CarpetLogic**: program a bot in your browser with a graph of nodes instead of a script. See below.
- **Carpet itself**: fake players, Scarpet and the rules you already know.

![/auto-setup](https://raw.githubusercontent.com/AndrewCTF/Carpet-PvP/main/docs/images/auto-setup-fight.png)

## What you need

| Minecraft | Java | Fabric Loader | Fabric API |
|---|---|---|---|
| 1.21.11 | 21 or newer | 0.19.2 or newer | any for 1.21.11; built and tested with 0.141.6+1.21.11 |
| 26.2 | 25 or newer | 0.19.2 or newer | any for 26.2; built and tested with 0.161.0+26.2 |
| 26.3 | 25 or newer | 0.19.2 or newer | any for 26.3; built and tested with 0.161.0+26.3 |

Fabric API is required: without it the game stops at start-up and says so. The Minecraft launcher brings the
right Java by itself; on a server you install it yourself.

## Install

**Singleplayer**

1. Install [Fabric Loader](https://fabricmc.net/use/installer/) for your Minecraft version.
2. Download [Fabric API](https://modrinth.com/mod/fabric-api) and Carpet PvP, both for the same Minecraft
   version, and put the two jars in your `mods` folder (`.minecraft/mods`).
3. Start the game with the Fabric profile and open a world.

**Server**

1. Set up a [Fabric server](https://fabricmc.net/use/server/) for your Minecraft version.
2. Put the Fabric API jar and the Carpet PvP jar in the server's `mods` folder and start it.
3. Players join with a normal client. They do not need the mod.

## How to use it

Use release 19 or newer.

1. **Get a bot.** Be in survival mode and type `/bot spawn`. A bot in diamond armour with a sword appears three
   blocks in front of you and attacks you. A bot leaves a player in creative mode alone.
2. **Get more.** Type `/bot spawn` again: they are named `Bot1`, `Bot2` and so on.
3. **Make one stop.** `/bot stop Bot1` makes it stand still. `/player Bot1 kill` removes it.
4. **Pick the weapon and the level.** `/bot spawn Tom mace expert` spawns a bot named Tom that fights with a
   mace at the hardest level. The weapons are `sword`, `smp`, `mace`, `crystal`, `anchor` and `ranged`; the
   levels are `beginner`, `casual`, `average`, `skilled` and `expert`.
5. **Get the same gear as the bot.** `/bot kit give @s sword` gives you its kit, and `/bot kit restore` gives
   you back what you had.
6. **Use buttons instead.** `/bot` opens a menu with the bots, their settings and the kits.
7. **Have a proper match.** `/auto-setup sword average` builds a fenced arena next to you, gives you and a bot
   the same kit, counts down, keeps the score and offers a rematch. `/auto-setup stop` puts every block and
   your inventory back.
8. **Watch two bots fight.** `/bot spawn A sword`, `/bot spawn B sword`, then `/bot duel A B`.

Every player may use `/bot` by default. A server admin restricts it to operators with
`/carpet commandBot ops`.

![The bot menu](https://raw.githubusercontent.com/AndrewCTF/Carpet-PvP/main/docs/images/bot-gui.png)

## Program a bot in your browser

CarpetLogic is a visual editor: you chain nodes (move, look, fight, wait, branch, loop) and the bot does
what the graph says.

1. In game, run `/carpetlogic open` and click **open in browser** in chat. It is for operators by
   default; `/carpet commandCarpetLogic true` opens it to everyone.
2. In the editor, press **Spawn** in the **Bots** panel. The bot appears where you are standing.
3. Press a preset such as *W-Tap* or *Crit Chain* on the empty canvas, or click nodes in the library on
   the left to chain them after `Start`.
4. Choose the bot under the canvas and press **Run**.

Programs save themselves to the world folder. On a server that is not your own computer the admin has
to open the editor up first: see the
[CarpetLogic page](https://github.com/AndrewCTF/Carpet-PvP/blob/main/docs/CarpetLogic.md#getting-started).

![The CarpetLogic editor](https://raw.githubusercontent.com/AndrewCTF/Carpet-PvP/main/docs/images/carpetlogic-editor.png)

## Versions

| Platform | Minecraft |
|---|---|
| Fabric | 1.21.11, 26.2, 26.3 |
| Paper | 1.21.11, 26.2, 26.3: a plugin with the bots, attached to each release on GitHub |

Java and Fabric API versions are in [What you need](#what-you-need). Minecraft 26.1.2 stays on release 17.

## What is not there yet

- The crystal bot fights with crystals but does not yet use respawn anchors, take the low ground or combine
  a knockback hit with a crystal in a live fight.
- The SMP bot is about even with a sword bot of the same level; it should be ahead.
- The ranged bot lays its TNT minecart trap but does not light it reliably.

## Links

- [Documentation](https://github.com/AndrewCTF/Carpet-PvP/tree/main/docs)
- [Source and issues](https://github.com/AndrewCTF/Carpet-PvP)
- [Discord](https://discord.gg/PAbydjFxKs)
