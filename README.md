# Carpet PvP 
<img src="https://github.com/AndrewCTF/Carpet-PvP/blob/main/icon.png" alt="Carpet PvP Practice" width="50" height="50">

![GitHub all releases](https://img.shields.io/github/downloads/AndrewCTF/Carpet-PvP/total?style=for-the-badge)
![GitHub Repo stars](https://img.shields.io/github/stars/AndrewCTF/Carpet-PvP?style=for-the-badge)
![GitHub forks](https://img.shields.io/github/forks/AndrewCTF/Carpet-PvP?style=for-the-badge)

Carpet PvP is a fork of TheobaldTheBird's Carpet PvP, we aim to provide frequent updates so that Carpet PvP will be supported as soon as possible.

Discord: [Carpet PvP Support](https://discord.gg/PAbydjFxKs)\
Support this project: [Buy Me a Coffee](https://buymeacoffee.com/andrewyong)

## Supported versions:

26.3 and 26.2. For 26.1.2 use release 17.

## Installation

1. Download the latest release from the [Releases](https://github.com/AndrewCTF/Carpet-PvP/releases) page
2. Place the `.jar` file in your `mods` folder
3. Ensure you have [Fabric Loader](https://fabricmc.net/use/installer/) and [Fabric API](https://modrinth.com/mod/fabric-api) installed

## Installation

1. Download the latest release from the [Releases](https://github.com/AndrewCTF/Carpet-PvP/releases) page
2. Place the `.jar` file in your `mods` folder
3. Ensure you have Fabric Loader and Fabric API installed
4. Supported: Minecraft 26.3 and 26.2

## Contributing

Contribute to Carpet PvP so that we can improve and make this mod better.
  
## Build

Requirements: **Java 25**, Gradle wrapper. Every supported Minecraft version is built from the same source tree; the version-specific parts are marked with [Stonecutter](https://stonecutter.kikugie.dev/) comments.
- Build every version: ./gradlew build -x test (jars land in versions/<minecraft>/build/libs)
- Build one version: ./gradlew :26.3:build -x test
- Run client (dev): ./gradlew :26.3:runClient
- Run server (dev): ./gradlew :26.3:runServer
- Check that every mixin still matches its target: ./gradlew :26.3:runServer -PmixinAudit

## Features

- All original Carpet PvP functionality, Breaks the Map.
- Bot armor auto-equipping system
- Extended player commands
- Scarpet scripting integration
- Comprehensive testing framework
- 1.8 combat features (Spam clicking and block hitting)

## Star History

[![Star History Chart](https://api.star-history.com/svg?repos=andrewctf/carpet-pvp&type=Date)]()
