package net.fabricmc.fabric.api.client.screen.v1;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
public final class ScreenEvents{
  public interface AfterInit{ void afterInit(Minecraft c, Screen s, int w, int h); }
  public interface Remove{ void onRemove(Screen s); }
  public interface AfterExtract{ void afterExtract(Screen s, GuiGraphicsExtractor g, int x, int y, float d); }
  public static class Ev<T>{ public void register(T t){} }
  public static final Ev<AfterInit> AFTER_INIT=new Ev<>();
  public static Ev<Remove> remove(Screen s){return null;}
  public static Ev<AfterExtract> afterExtract(Screen s){return null;}
}
