package com.mojang.brigadier.arguments;
import com.mojang.brigadier.context.CommandContext;
public class IntegerArgumentType implements ArgumentType<Integer>{ public static IntegerArgumentType integer(){return null;} public static IntegerArgumentType integer(int a){return null;} public static IntegerArgumentType integer(int a,int b){return null;} public static int getInteger(CommandContext<?> c,String n){return 0;} }
