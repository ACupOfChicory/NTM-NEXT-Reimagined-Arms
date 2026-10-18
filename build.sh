#!/bin/sh
# Addon for HBM NTM guns. Server: hold-to-aim. Client: smooth per-frame camera recoil, spring model recoil,
# gun moved toward the screen centre, off-hand item hidden. NTM itself untouched.
set -e
cd "$(dirname "$(readlink -f "$0")")"
P=$HOME/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher
J=${JDK:-$P/java/java-runtime-epsilon}/bin
L=$P/libraries
M=$P/instances/NTM-NeoForge-26.2-private/minecraft/mods
OUT=ntm-gunfeel-0.1.147-alpha.jar
CP=$L/com/mojang/minecraft/26.2/minecraft-26.2-client.jar
CP=$CP:$L/net/neoforged/neoforge/26.2.0.90/neoforge-26.2.0.90-universal.jar
CP=$CP:$L/net/neoforged/fancymodloader/loader/11.0.16/loader-11.0.16.jar
CP=$CP:$L/net/fabricmc/sponge-mixin/0.17.4+mixin.0.8.7/sponge-mixin-0.17.4+mixin.0.8.7.jar
CP=$CP:$(ls $M/ntm-next-neoforge-*.jar):$(ls $L/net/neoforged/bus/*/bus-*.jar | tail -1)
CP=$CP:/home/acupofchicory/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher/libraries/org/slf4j/slf4j-api/2.0.9/slf4j-api-2.0.9.jar
CP=$CP:$L/net/neoforged/mergetool/2.0.7/mergetool-2.0.7-api.jar:$L/it/unimi/dsi/fastutil/8.5.18/fastutil-8.5.18.jar
CP=$CP:$L/com/mojang/brigadier/1.3.10/brigadier-1.3.10.jar:$L/com/mojang/datafixerupper/10.0.21/datafixerupper-10.0.21.jar
CP=$CP:$L/com/google/guava/guava/33.6.0-jre/guava-33.6.0-jre.jar
CP=$CP:$L/io/netty/netty-buffer/4.2.15.Final/netty-buffer-4.2.15.Final.jar:$L/io/netty/netty-common/4.2.15.Final/netty-common-4.2.15.Final.jar
for d in $L/org/jspecify $L/org/joml; do
    for j in $(find $d -name '*.jar' 2>/dev/null); do CP=$CP:$j; done
done
rm -rf build && mkdir -p build/jar
"$J/javac" --release 25 -proc:none -nowarn -cp "$CP" -d build/jar $(find src -name '*.java')
cp -r res/. build/jar/
rm -f $OUT && "$J/jar" cf $OUT -C build/jar .
echo "built $OUT"
