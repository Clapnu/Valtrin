package com.mojang.brigadier.suggestion;
import java.util.concurrent.CompletableFuture;
public class SuggestionsBuilder{ public SuggestionsBuilder suggest(String s){return this;} public SuggestionsBuilder suggest(int i){return this;} public Suggestions build(){return null;} public CompletableFuture<Suggestions> buildFuture(){return null;} }
