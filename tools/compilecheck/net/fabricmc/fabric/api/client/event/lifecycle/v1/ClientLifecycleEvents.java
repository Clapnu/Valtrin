package net.fabricmc.fabric.api.client.event.lifecycle.v1;
import net.minecraft.client.Minecraft;
public final class ClientLifecycleEvents{ public interface Stopping{ void onClientStopping(Minecraft c);} public static class Ev<T>{ public void register(T t){} } public static final Ev<Stopping> CLIENT_STOPPING=new Ev<>(); }
