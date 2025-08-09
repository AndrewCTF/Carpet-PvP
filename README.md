# Carpet PvP

Carpet PvP is a fork of TheobaldTheBird's Carpet PvP, we aim to provide frequent updates so that Carpet PvP will be supported as soon as possible.

## Supported versions:

1.21.6-1.21.8

## Installation

1. Download the latest release from the [Releases](https://github.com/AndrewCTF/Carpet-PvP/releases) page
2. Place the `.jar` file in your `mods` folder
3. Ensure you have [Fabric Loader](https://fabricmc.net/use/installer/) and [Fabric API](https://modrinth.com/mod/fabric-api) installed
4. Supported: Minecraft [1.21.6, 1.21.7, 1.21.8] (fabric.mod.json range: 1.21–1.22)

## Contributing

Contribute to Carpet PvP so that we can improve and make this mod better.

## Contributers
- @AndrewCTF (Owner)
- @Horrifi0
  
## Build

Requirements: Java 21, Gradle wrapper. Manual test steps:
- Build: ./gradlew build
- Run client: ./gradlew runClient
- Run server: ./gradlew runServer
- Build jar: ./gradlew build -x test
- Run client (dev): ./gradlew runClient
- Run server (dev): ./gradlew runServer
- Publish to local maven: ./gradlew publishToMavenLocal

## Features

- All original Carpet PvP functionality, Breaks the Map.
- Bot armor auto-equipping system
- Extended player commands
- Scarpet scripting integration
- Comprehensive testing framework
- 1.8 combat features
