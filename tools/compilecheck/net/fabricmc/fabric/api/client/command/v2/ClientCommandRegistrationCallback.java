package net.fabricmc.fabric.api.client.command.v2;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandBuildContext;
public interface ClientCommandRegistrationCallback{ void register(CommandDispatcher<FabricClientCommandSource> d, CommandBuildContext c);
 public static final Ev EVENT=new Ev(); class Ev{ public void register(ClientCommandRegistrationCallback c){} } }
