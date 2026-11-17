Real compile check without Gradle (Linux sandbox, javac 21, no Fabric/brigadier jars):
1. Rewrite the class version of the merged Minecraft jar to 65 (Python: copy every .class with bytes 6-7 set to 0x0041) -> mc65.jar.
   Jar: Template/.gradle/loom-cache/minecraftMaven/net/minecraft/minecraft-merged-*/26.2/*.jar (stage it with the device bridge).
2. javac -proc:none -Xlint:none -nowarn -cp mc65.jar:gson.jar:slf4j-api.jar -sourcepath tools/compilecheck -d out $(find src/main/java -name '*.java')
The stubs here only replace Fabric API / brigadier / mixin / DFU; every net.minecraft call is checked against the real 26.2 classes.
Stub signatures for Fabric are guesses: a Fabric API change is not detected here.
