package com.mojang.brigadier.arguments;
import com.mojang.brigadier.context.CommandContext;
public class BoolArgumentType implements ArgumentType<Boolean>{ public static BoolArgumentType bool(){return null;} public static boolean getBool(CommandContext<?> c,String n){return false;} }
