package net.fabricmc.fabric.api.client.event.lifecycle.v1;
import net.minecraft.client.Minecraft;
public final class ClientTickEvents{ public interface EndTick{ void onEndTick(Minecraft c);} public static class Ev<T>{ public void register(T t){} } public static final Ev<EndTick> END_CLIENT_TICK=new Ev<>(); }
