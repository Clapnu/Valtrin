package net.fabricmc.fabric.api.client.screen.v1;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
public final class ScreenMouseEvents {
  public interface AllowMouseClick { boolean allowMouseClick(Screen screen, MouseButtonEvent click); }
  public interface Ev<T> { void register(T t); }
  public static Ev<AllowMouseClick> allowMouseClick(Screen s) { return t -> {}; }
}
