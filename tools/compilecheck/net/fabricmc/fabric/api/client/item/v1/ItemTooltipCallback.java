package net.fabricmc.fabric.api.client.item.v1;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.*;
import net.minecraft.world.item.TooltipFlag;
import java.util.List;
public interface ItemTooltipCallback{ void getTooltip(ItemStack stack, Item.TooltipContext context, TooltipFlag type, List<Component> lines);
 public static final Ev EVENT=new Ev(); class Ev{ public void register(ItemTooltipCallback c){} } }
