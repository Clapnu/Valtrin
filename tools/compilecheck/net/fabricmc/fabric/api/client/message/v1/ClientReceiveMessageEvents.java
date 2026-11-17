package net.fabricmc.fabric.api.client.message.v1;
import net.minecraft.network.chat.Component;
public final class ClientReceiveMessageEvents{ public interface Game{ void onReceiveGameMessage(Component m, boolean overlay); } public static class Ev<T>{ public void register(T t){} } public static final Ev<Game> GAME=new Ev<>(); }
