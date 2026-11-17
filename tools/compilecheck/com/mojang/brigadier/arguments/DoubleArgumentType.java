package com.mojang.brigadier.arguments;
import com.mojang.brigadier.context.CommandContext;
public class DoubleArgumentType implements ArgumentType<Double>{ public static DoubleArgumentType doubleArg(){return null;} public static DoubleArgumentType doubleArg(double a){return null;} public static DoubleArgumentType doubleArg(double a,double b){return null;} public static double getDouble(CommandContext<?> c,String n){return 0;} }
