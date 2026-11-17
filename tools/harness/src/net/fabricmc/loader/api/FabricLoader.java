package net.fabricmc.loader.api;
import java.nio.file.*;
public class FabricLoader { static FabricLoader I=new FabricLoader(); public static FabricLoader getInstance(){return I;} public Path getConfigDir(){ try{return Files.createTempDirectory("cfg");}catch(Exception e){throw new RuntimeException(e);} } }
