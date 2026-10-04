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
- **CarpetLogic**: a browser editor for programming a bot with a node graph, with expressions, autosave and a
  node that runs a Scarpet snippet. An admin sign-in for changing rules can be switched on.
- **Carpet itself**: fake players, Scarpet and the rules you already know.

![/auto-setup](https://raw.githubusercontent.com/AndrewCTF/Carpet-PvP/main/docs/images/auto-setup-fight.png)

## Quick start

1. Install [Fabric Loader](https://fabricmc.net/use/installer/) and
   [Fabric API](https://modrinth.com/mod/fabric-api) on the server, and put the jar for your Minecraft version
   in `mods`.
2. Join and run `/auto-setup sword average`, or open the menu with `/bot gui`.

![The bot menu](https://raw.githubusercontent.com/AndrewCTF/Carpet-PvP/main/docs/images/bot-gui.png)

## Versions

| Platform | Minecraft |
|---|---|
| Fabric | 1.21.11, 26.2, 26.3 |
| Paper | 1.21.11, 26.2, 26.3: a plugin with the bots, attached to each release on GitHub |

Java 25. Minecraft 26.1.2 stays on release 17.

## What is not there yet

- The crystal bot fights with crystals but does not yet use respawn anchors, take the low ground or combine
  a knockback hit with a crystal in a live fight.
- The SMP bot is about even with a sword bot of the same level; it should be ahead.
- The ranged bot lays its TNT minecart trap but does not light it reliably.

## Links

- [Documentation](https://github.com/AndrewCTF/Carpet-PvP/tree/main/docs)
- [Source and issues](https://github.com/AndrewCTF/Carpet-PvP)
- [Discord](https://discord.gg/PAbydjFxKs)

![The CarpetLogic editor](https://raw.githubusercontent.com/AndrewCTF/Carpet-PvP/main/docs/images/carpetlogic-program.png)
